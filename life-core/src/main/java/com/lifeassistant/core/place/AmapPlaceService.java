package com.lifeassistant.core.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 高德 Web 服务：搜地点（带照片、评分、人均）、驾车和公交路线、静态地图。页面上的景点、美食、行程地图都从这里取。
 * 结果在内存里缓存一天，调用之间留间隔，免得超过个人 Key 的每秒次数。
 */
@Slf4j
@Service
public class AmapPlaceService {

    /** 景点、美食、酒店在高德的分类编码 */
    public static final String SPOT_TYPES = "110000";
    public static final String FOOD_TYPES = "050000";
    public static final String HOTEL_TYPES = "100000";

    private static final long CACHE_MILLIS = 24 * 3600 * 1000L;
    private static final int CACHE_SIZE = 2000;
    private static final long MIN_INTERVAL_MILLIS = 220;
    /** 静态地图一条折线最多放这么多个点，多了 URL 太长 */
    private static final int MAX_PATH_POINTS = 90;
    /** 一张地图最多标的点数，超出的不画也不查路线 */
    private static final int MAX_MARKERS = 10;
    private static final int MAP_CACHE_SIZE = 80;
    /** 一张地图最多再画几条其他天的路线，每条最多这么多个点（URL 长度有限） */
    private static final int MAX_LINES = 6;
    private static final int LINE_POINTS = 40;

    /** 高德照片原图几百 KB，列表里用这个参数取 480×360 的缩略图 */
    private static final String THUMB = "?operate=merge&w=480&h=360&position=5";

    private final RestClient restClient;
    private final String key;
    private final Map<String, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    });
    /** 地图图片单独缓存，按个数限制，免得几百 KB 的图片把上面的缓存撑大 */
    private final Map<String, byte[]> mapCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAP_CACHE_SIZE;
        }
    });
    private long lastCall;

    public AmapPlaceService(RestClient.Builder externalRestClientBuilder, LifeProperties properties) {
        this.key = properties.getAmap().getKey();
        this.restClient = externalRestClientBuilder.clone().baseUrl("https://restapi.amap.com").build();
    }

    /**
     * 一个地点。category 是高德分类的最后一级（如"博物馆"），photo 是第一张照片，cost 是人均（元），
     * distance 是离搜索中心的米数（周边搜索才有），tags 是高德给的特色标签（如"夜景""演出"），最多 3 个。
     */
    public record Place(String id, String name, String category, String address, String area, Double lng,
                        Double lat, Double rating, Integer cost, String photo, List<String> photos, String tel,
                        Integer distance, List<String> tags) {
    }

    /** 一段驾车路线：分钟、公里、打车费估算（元，可能为空）、沿途坐标（经度,纬度）。 */
    public record Route(int minutes, double km, Integer taxi, List<double[]> path) {
    }

    private record Cached(long at, Object value) {
    }

    public boolean available() {
        return StringUtils.hasText(key);
    }

    /** 关键字搜地点，city 限定在城市内；types 是高德分类编码，可空。 */
    public List<Place> search(String keywords, String city, String types, int size) {
        return cached("search|" + keywords + "|" + city + "|" + types + "|" + size, () -> places(get("/v5/place/text",
                "keywords", nullToEmpty(keywords), "types", nullToEmpty(types), "region", nullToEmpty(city),
                "city_limit", StringUtils.hasText(city) ? "true" : "false", "page_size", String.valueOf(size),
                "show_fields", "photos,business")));
    }

    /** 周边搜索：以坐标为中心、半径 radius 米内，按综合热度排。 */
    public List<Place> around(double lng, double lat, String keywords, String types, int radius, int size) {
        String location = coord(lng, lat);
        return cached("around|" + location + "|" + keywords + "|" + types + "|" + radius + "|" + size,
                () -> places(get("/v5/place/around", "location", location, "keywords", nullToEmpty(keywords),
                        "types", nullToEmpty(types), "radius", String.valueOf(radius), "sortrule", "weight",
                        "page_size", String.valueOf(size), "show_fields", "photos,business")));
    }

    /** 按名称在城市里找最像的那个地点，名称对不上（比如"入住酒店"这种泛称）返回空。 */
    public Optional<Place> find(String name, String city) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return search(name, city, null, 3).stream().filter(p -> similarName(name, p.name())).findFirst();
    }

    /**
     * 一个定位结果：高德坐标系（GCJ-02）下的经纬度，所在城市（去掉"市"字，直辖市用省名）、区县和详细地址。
     */
    public record Located(double lng, double lat, String city, String district, String address) {
    }

    /**
     * 把浏览器给的 GPS 坐标（WGS-84）转成高德坐标，再查所在城市。浏览器坐标直接用在高德上会偏几百米。
     * 不缓存，也不记录：位置用完就丢。
     */
    public Located locate(double gpsLng, double gpsLat) {
        String[] xy = get("/v3/assistant/coordinate/convert", "locations", coord(gpsLng, gpsLat), "coordsys", "gps")
                .path("locations").asText("").split(";")[0].split(",");
        if (xy.length != 2) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "坐标转换失败");
        }
        double lng = Double.parseDouble(xy[0]);
        double lat = Double.parseDouble(xy[1]);
        JsonNode regeo = get("/v3/geocode/regeo", "location", coord(lng, lat)).path("regeocode");
        JsonNode c = regeo.path("addressComponent");
        // 直辖市的 city 是空数组，用省名
        String city = c.path("city").isTextual() && !c.path("city").asText().isBlank()
                ? c.path("city").asText() : c.path("province").asText("");
        return new Located(lng, lat, city.replaceFirst("市$", ""), text(c, "district"),
                text(regeo, "formatted_address"));
    }

    /** 城市（或地名）中心点的坐标，查不到返回空。 */
    public Optional<double[]> center(String place) {
        double[] point = cached("geo|" + place, () -> {
            String[] xy = get("/v3/geocode/geo", "address", place).path("geocodes").path(0).path("location").asText("").split(",");
            return xy.length == 2 ? new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])} : null;
        });
        return Optional.ofNullable(point);
    }

    /** 两点间驾车路线，查不到返回空。 */
    public Optional<Route> driving(double[] from, double[] to) {
        String origin = coord(from[0], from[1]);
        String destination = coord(to[0], to[1]);
        Route route = cached("drive|" + origin + "|" + destination, () -> {
            JsonNode body = get("/v3/direction/driving", "origin", origin, "destination", destination,
                    "strategy", "0", "extensions", "all").path("route");
            JsonNode path = body.path("paths").path(0);
            if (path.isMissingNode()) {
                return null;
            }
            List<double[]> points = new ArrayList<>();
            for (JsonNode step : path.path("steps")) {
                points.addAll(parsePoints(step.path("polyline").asText("")));
            }
            // 打车费只在 extensions=all 时返回
            Double taxi = number(body, "taxi_cost");
            return new Route((int) Math.round(path.path("duration").asDouble() / 60),
                    Math.round(path.path("distance").asDouble() / 100) / 10.0,
                    taxi == null ? null : (int) Math.round(taxi), points);
        });
        return Optional.ofNullable(route);
    }

    /**
     * 一段公交路线（地铁优先）：分钟（含步行和等车）、票价（元，每人，可能为空）、步行公里、依次乘坐的线路（如"地铁8号线"）。
     */
    public record Transit(int minutes, Double fee, double walkKm, List<String> lines) {
    }

    /** 城市的 citycode（如北京 010），查公交路线要用；查不到返回空。 */
    public Optional<String> cityCode(String city) {
        if (!StringUtils.hasText(city)) {
            return Optional.empty();
        }
        String code = cached("citycode|" + city, () -> text(get("/v3/geocode/geo", "address", city)
                .path("geocodes").path(0), "citycode"));
        return Optional.ofNullable(code);
    }

    /** 某个坐标所在城市的 citycode，行程目的地写得不规范（"新疆巴音郭楞（库尔勒·轮台）"）时用；查不到返回空。 */
    public Optional<String> cityCodeAt(double lng, double lat) {
        String location = coord(lng, lat);
        String code = cached("citycode-at|" + location, () -> text(get("/v3/geocode/regeo", "location", location)
                .path("regeocode").path("addressComponent"), "citycode"));
        return Optional.ofNullable(code);
    }

    /** 两点间公交路线，按"地铁优先"取第一条；cityCode 是所在城市。没有公交方案（太近、跨城、没数据）返回空。 */
    public Optional<Transit> transit(double[] from, double[] to, String cityCode) {
        String origin = coord(from[0], from[1]);
        String destination = coord(to[0], to[1]);
        Transit transit = cached("transit|" + origin + "|" + destination + "|" + cityCode, () -> {
            JsonNode t = get("/v5/direction/transit/integrated", "origin", origin, "destination", destination,
                    "city1", cityCode, "city2", cityCode, "strategy", "7", "show_fields", "cost")
                    .path("route").path("transits").path(0);
            if (t.isMissingNode()) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode segment : t.path("segments")) {
                String name = text(segment.path("bus").path("buslines").path(0), "name");
                if (name != null) {
                    // "地铁8号线(瀛海--朱辛庄)" 只留线路名
                    lines.add(name.replaceFirst("\\(.*$", ""));
                }
            }
            if (lines.isEmpty()) {
                return null;
            }
            Double duration = number(t.path("cost"), "duration");
            Double walk = number(t, "walking_distance");
            return new Transit((int) Math.round((duration == null ? 0 : duration) / 60), number(t.path("cost"), "transit_fee"),
                    Math.round((walk == null ? 0 : walk) / 100) / 10.0, lines);
        });
        return Optional.ofNullable(transit);
    }

    /** 地图上额外画的一条线（比如其他几天的路线）：颜色是 6 位十六进制，点按顺序连。 */
    public record Line(String color, List<double[]> points) {
    }

    /**
     * 一张静态地图要画什么：stops 按顺序标 1、2、3…（marks 为 false 时不画标记，由页面自己叠可点的编号），
     * route 为 true 时用驾车路线把 stops 连起来；others 是橙色的小标记（比如候选地点，标 A、B、C…）。
     * w、h 是显示尺寸，按两倍清晰度生成。color 是 stops 和路线的颜色（6 位十六进制，空为蓝色）；
     * circle 大于 0 时以第一个 stop 为圆心画这么多米的范围圈；lines 是其他几天的路线。
     * center、zoom 都给了就按它显示（页面要自己算标记位置时用），否则高德自动缩放到能看见所有点。
     */
    public record MapSpec(List<double[]> stops, boolean route, List<double[]> others, int w, int h, String color,
                          int circle, List<Line> lines, double[] center, Integer zoom, boolean marks) {
    }

    public byte[] staticMap(List<double[]> allStops, boolean route, List<double[]> allOthers, int width, int height) {
        return staticMap(new MapSpec(allStops, route, allOthers, width, height, null, 0, List.of(), null, null, true));
    }

    /** 静态地图 PNG，画法见 MapSpec。其他几天的路线只用已经查过的驾车路线，没查过的画直线，不额外调高德。 */
    public byte[] staticMap(MapSpec spec) {
        String main = spec.color() == null ? "0x2563EB" : "0x" + spec.color();
        List<double[]> stops = spec.stops().subList(0, Math.min(spec.stops().size(), MAX_MARKERS));
        List<double[]> others = spec.others().subList(0, Math.min(spec.others().size(), MAX_MARKERS));
        List<Line> lines = spec.lines() == null ? List.of() : spec.lines();
        int w = Math.max(100, Math.min(spec.w(), 1024));
        int h = Math.max(100, Math.min(spec.h(), 1024));
        List<String> params = new ArrayList<>(List.of("size", w + "*" + h, "scale", "2"));
        StringBuilder markers = new StringBuilder();
        for (int i = 0; spec.marks() && i < stops.size(); i++) {
            markers.append(markers.isEmpty() ? "" : "|").append("mid,").append(main).append(",").append(i < 9 ? String.valueOf(i + 1) : "0")
                    .append(":").append(coord(stops.get(i)[0], stops.get(i)[1]));
        }
        for (int i = 0; i < others.size(); i++) {
            markers.append(markers.isEmpty() ? "" : "|").append("small,0xF97316,").append((char) ('A' + i))
                    .append(":").append(coord(others.get(i)[0], others.get(i)[1]));
        }
        if (!markers.isEmpty()) {
            params.add("markers");
            params.add(markers.toString());
        }
        List<String> paths = new ArrayList<>();
        if (spec.circle() > 0 && !stops.isEmpty()) {
            paths.add("2," + main + ",0.6," + main + ",0.1:" + String.join(";", ring(stops.get(0), spec.circle()).stream()
                    .map(p -> coord(p[0], p[1])).toList()));
        }
        for (Line extra : lines.subList(0, Math.min(lines.size(), MAX_LINES))) {
            List<double[]> pts = extra.points().subList(0, Math.min(extra.points().size(), MAX_MARKERS));
            List<double[]> line = new ArrayList<>();
            for (int i = 0; i + 1 < pts.size(); i++) {
                line.addAll(cachedPath(pts.get(i), pts.get(i + 1)));
            }
            if (line.size() > 1) {
                paths.add("4,0x" + extra.color() + ",0.7,,:" + String.join(";", sample(line, LINE_POINTS).stream()
                        .map(p -> coord(p[0], p[1])).toList()));
            }
        }
        if (spec.route() && stops.size() > 1) {
            List<double[]> line = new ArrayList<>();
            for (int i = 0; i + 1 < stops.size(); i++) {
                List<double[]> segment = List.of(stops.get(i), stops.get(i + 1));
                try {
                    segment = driving(stops.get(i), stops.get(i + 1)).map(Route::path).orElse(segment);
                } catch (RuntimeException e) {
                    // 某一段路线查不到就画直线，不影响整张图
                    log.warn("地图路线 {} 段查询失败：{}", i + 1, e.getMessage());
                }
                line.addAll(segment);
            }
            paths.add("6," + main + ",0.9,,:" + String.join(";", sample(line, MAX_PATH_POINTS).stream()
                    .map(p -> coord(p[0], p[1])).toList()));
        }
        if (!paths.isEmpty()) {
            params.add("paths");
            params.add(String.join("|", paths));
        }
        if (stops.isEmpty() && others.isEmpty() && lines.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "地图上至少要有一个点");
        }
        if (spec.center() != null && spec.zoom() != null) {
            params.addAll(List.of("location", coord(spec.center()[0], spec.center()[1]),
                    "zoom", String.valueOf(Math.max(3, Math.min(spec.zoom(), 17)))));
        } else if (stops.size() + others.size() == 1 && spec.circle() <= 0 && lines.isEmpty()) {
            double[] only = stops.isEmpty() ? others.get(0) : stops.get(0);
            params.addAll(List.of("location", coord(only[0], only[1]), "zoom", "14"));
        }
        String cacheKey = String.join("|", params);
        byte[] hit = mapCache.get(cacheKey);
        if (hit != null) {
            return hit;
        }
        byte[] png = loadMap(params);
        mapCache.put(cacheKey, png);
        return png;
    }

    /**
     * 驾车距离矩阵：用高德距离测量接口，每个终点查一次（起点一次最多 100 个）。
     * 返回 [起点][终点] 的 {秒, 米}，查不到的为空。
     */
    public long[][][] drivingMatrix(List<double[]> points) {
        int n = points.size();
        long[][][] out = new long[n][n][];
        for (int j = 0; j < n; j++) {
            List<Integer> from = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (i != j) {
                    from.add(i);
                }
            }
            if (from.isEmpty()) {
                continue;
            }
            String origins = String.join("|", from.stream().map(i -> coord(points.get(i)[0], points.get(i)[1])).toList());
            String destination = coord(points.get(j)[0], points.get(j)[1]);
            JsonNode results = cached("distance|" + origins + "|" + destination,
                    () -> get("/v3/distance", "origins", origins, "destination", destination, "type", "1").path("results"));
            for (JsonNode r : results) {
                int k = r.path("origin_id").asInt(0) - 1;
                if (k >= 0 && k < from.size() && r.hasNonNull("duration")) {
                    out[from.get(k)][j] = new long[]{r.path("duration").asLong(), r.path("distance").asLong()};
                }
            }
        }
        return out;
    }

    /**
     * 画路线用的坐标：1.2 公里内连直线（按步行算），远的用高德驾车路线（有缓存），查不到连直线。
     */
    public List<double[]> path(double[] from, double[] to) {
        double dLat = Math.toRadians(to[1] - from[1]);
        double dLng = Math.toRadians(to[0] - from[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(from[1]))
                * Math.cos(Math.toRadians(to[1])) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double km = 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        if (km <= 1.2 || !available()) {
            return List.of(from, to);
        }
        try {
            return driving(from, to).map(Route::path).filter(p -> !p.isEmpty()).orElse(List.of(from, to));
        } catch (RuntimeException e) {
            return List.of(from, to);
        }
    }

    /** 两点间已经查过的驾车路线；没查过的直接连直线，不为画图额外调高德。 */
    private List<double[]> cachedPath(double[] from, double[] to) {
        Cached hit = cache.get("drive|" + coord(from[0], from[1]) + "|" + coord(to[0], to[1]));
        if (hit != null && hit.value() instanceof Route r && !r.path().isEmpty()) {
            return r.path();
        }
        return List.of(from, to);
    }

    /** 以 center 为圆心、meters 米为半径的一圈点（首尾相接），用来画范围圈。 */
    private static List<double[]> ring(double[] center, int meters) {
        List<double[]> out = new ArrayList<>();
        double dLat = meters / 111_320.0;
        double dLng = meters / (111_320.0 * Math.cos(Math.toRadians(center[1])));
        for (int i = 0; i <= 36; i++) {
            double a = Math.toRadians(i * 10);
            out.add(new double[]{center[0] + dLng * Math.cos(a), center[1] + dLat * Math.sin(a)});
        }
        return out;
    }

    /** 调高德静态地图接口取 PNG。 */
    private byte[] loadMap(List<String> params) {
        throttle();
        try {
            return restClient.get().uri(b -> {
                b.path("/v3/staticmap").queryParam("key", key);
                for (int i = 0; i + 1 < params.size(); i += 2) {
                    b.queryParam(params.get(i), "{p" + i + "}");
                }
                Map<String, Object> vars = new LinkedHashMap<>();
                for (int i = 0; i + 1 < params.size(); i += 2) {
                    vars.put("p" + i, params.get(i + 1));
                }
                return b.build(vars);
            }).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            log.warn("高德静态地图失败：{}", e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "地图暂时生成不了", e);
        }
    }

    /** 两个地点名是不是同一处：去掉括号里的分店名和标点后，一个包含另一个，或者共有一半以上的两字词。 */
    public static boolean similarName(String wanted, String found) {
        if (wanted == null || found == null) {
            return false;
        }
        String a = core(wanted);
        String b = core(found);
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        int total = 0;
        int hit = 0;
        for (int i = 0; i + 1 < a.length(); i++) {
            total++;
            if (b.contains(a.substring(i, i + 2))) {
                hit++;
            }
        }
        return hit * 2 >= total;
    }

    private static String core(String name) {
        return name.replaceAll("[(（].*?[)）]", "").replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    /** 高德地点列表转成 Place；照片统一用 https，免得页面上混合内容被拦。 */
    private static List<Place> places(JsonNode body) {
        List<Place> out = new ArrayList<>();
        for (JsonNode p : body.path("pois")) {
            List<String> photos = new ArrayList<>();
            for (JsonNode photo : p.path("photos")) {
                String url = photo.path("url").asText("");
                if (!url.isBlank()) {
                    photos.add(url.replaceFirst("^http://", "https://"));
                }
            }
            String[] location = p.path("location").asText("").split(",");
            JsonNode biz = p.path("business");
            String[] types = p.path("type").asText("").split(";");
            out.add(new Place(p.path("id").asText(null), p.path("name").asText(), types[types.length - 1],
                    p.path("address").asText(null), text(biz, "business_area"),
                    location.length == 2 ? Double.valueOf(location[0]) : null,
                    location.length == 2 ? Double.valueOf(location[1]) : null,
                    number(biz, "rating"), cost(biz), photos.isEmpty() ? null : photos.get(0) + THUMB, photos,
                    text(biz, "tel"), p.path("distance").asText("").isBlank() ? null : p.path("distance").asInt(),
                    tags(biz)));
        }
        return out;
    }

    /** 高德的特色标签（keytag、rectag），去重后最多 3 个。 */
    private static List<String> tags(JsonNode biz) {
        Set<String> out = new LinkedHashSet<>();
        for (String field : List.of("keytag", "rectag")) {
            String value = text(biz, field);
            if (value != null) {
                for (String tag : value.split("[,;，；、|]")) {
                    if (!tag.isBlank() && tag.trim().length() <= 8) {
                        out.add(tag.trim());
                    }
                }
            }
        }
        return out.stream().limit(3).toList();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : null;
        return StringUtils.hasText(value) ? value : null;
    }

    private static Double number(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer cost(JsonNode biz) {
        Double cost = number(biz, "cost");
        return cost == null ? null : (int) Math.round(cost);
    }

    private static List<double[]> parsePoints(String polyline) {
        List<double[]> points = new ArrayList<>();
        for (String pair : polyline.split(";")) {
            String[] xy = pair.split(",");
            if (xy.length == 2) {
                points.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
            }
        }
        return points;
    }

    /** 均匀抽取最多 max 个点，保留首尾。 */
    private static List<double[]> sample(List<double[]> points, int max) {
        if (points.size() <= max) {
            return points;
        }
        List<double[]> out = new ArrayList<>();
        double step = (points.size() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) {
            out.add(points.get((int) Math.round(i * step)));
        }
        return out;
    }

    private static String coord(double lng, double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, Supplier<T> loader) {
        Cached hit = cache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_MILLIS) {
            return (T) hit.value();
        }
        T value = loader.get();
        cache.put(cacheKey, new Cached(System.currentTimeMillis(), value));
        return value;
    }

    /** 两次调用之间至少隔 MIN_INTERVAL_MILLIS。 */
    private void throttle() {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long next = Math.max(now, lastCall + MIN_INTERVAL_MILLIS);
            lastCall = next;
            wait = next - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 统一 GET：带 key，校验 status=1；超过每秒次数时等一秒重试一次。 */
    private JsonNode get(String path, String... params) {
        if (!available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置高德 Key（AMAP_KEY）");
        }
        for (int attempt = 0; ; attempt++) {
            throttle();
            JsonNode body;
            try {
                body = restClient.get().uri(b -> {
                    b.path(path).queryParam("key", key);
                    for (int i = 0; i + 1 < params.length; i += 2) {
                        if (!params[i + 1].isEmpty()) {
                            b.queryParam(params[i], params[i + 1]);
                        }
                    }
                    return b.build();
                }).retrieve().body(JsonNode.class);
            } catch (RestClientException e) {
                log.warn("调用高德 {} 失败：{}", path, e.getMessage());
                throw new BizException(ErrorCode.EXTERNAL_SERVICE, "高德暂时不可用", e);
            }
            if (body != null && "1".equals(body.path("status").asText())) {
                return body;
            }
            String info = body == null ? "空" : body.path("info").asText();
            if (attempt == 0 && info.contains("QPS")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "高德返回错误：" + info);
        }
    }
}
