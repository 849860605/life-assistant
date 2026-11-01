package com.lifeassistant.api.web;

import com.lifeassistant.api.auth.MediaAuth;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 地点和地图接口（高德）：首页的热门景点和美食、美食页、行程地图图片。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PlaceController {

    private final AmapPlaceService places;
    private final MediaAuth mediaAuth;

    /** 地图上最多标这么多个点，多了既看不清又会大量调用高德路线接口 */
    private static final int MAX_POINTS = 10;

    /**
     * GET /api/v1/places?kind=spot|food|hotel&city=西安：城市里的热门景点、美食或酒店；
     * 带 lng、lat 时搜周边（radius 米），带 keyword 时按关键字搜。按评分从高到低。
     */
    @GetMapping("/places")
    public ApiResponse<List<AmapPlaceService.Place>> list(@RequestParam(defaultValue = "spot") String kind,
                                                          @RequestParam(required = false) String city,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Double lng,
                                                          @RequestParam(required = false) Double lat,
                                                          @RequestParam(defaultValue = "3000") int radius,
                                                          @RequestParam(defaultValue = "10") int size) {
        String types = switch (kind) {
            case "food" -> AmapPlaceService.FOOD_TYPES;
            case "hotel" -> AmapPlaceService.HOTEL_TYPES;
            default -> AmapPlaceService.SPOT_TYPES;
        };
        int n = Math.max(1, Math.min(size, 25));
        List<AmapPlaceService.Place> found;
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        // 高德同时给关键字和景点分类时会忽略关键字，搜景点名就不带分类
        String typesWithKeyword = hasKeyword && "spot".equals(kind) ? null : types;
        if (lng != null && lat != null) {
            found = places.around(lng, lat, keyword, typesWithKeyword, Math.min(radius, 50000), n);
        } else if (hasKeyword) {
            found = places.search(keyword, city, typesWithKeyword, n);
        } else if ("spot".equals(kind)) {
            // "5A景区"搜出来的是城市最有名的那几个；小城市没有 5A 时退回普通景点
            found = new ArrayList<>(places.search("5A景区", city, null, n));
            if (found.size() < 4) {
                found = places.search("景点", city, types, n);
            }
        } else {
            // 美食、酒店按城市搜会搜到郊区的，改成在市中心周边按热度找
            String word = "food".equals(kind) ? "美食" : "酒店";
            found = places.center(city).map(c -> places.around(c[0], c[1], word, types, radius, n))
                    .orElseGet(() -> places.search(word, city, types, n));
        }
        return ApiResponse.ok(found.stream()
                .sorted(Comparator.comparing(AmapPlaceService.Place::rating, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());
    }

    /**
     * GET /api/v1/maps/static?stops=经度,纬度;经度,纬度&route=true&others=…&w=600&h=400&mt=票据：地图图片。
     * stops 按顺序标 1、2、3，route 为 true 时画驾车路线；others 是橙色的候选点，各最多 10 个。图片缓存一天。
     * 可选：color 路线颜色（6 位十六进制）、circle 范围圈半径（米，以第一个 stop 为圆心）、
     * lines 其他几天的路线（"颜色:经度,纬度;经度,纬度|颜色:…"，最多 6 条）、center + zoom 指定显示范围、
     * marks=false 不画 stops 的标记（页面自己叠可点的编号）。
     * &lt;image&gt; 带不了请求头，用 mt（下载票据）证明身份，也接受请求头里的登录 token。
     */
    @GetMapping("/maps/static")
    public ResponseEntity<byte[]> map(@RequestParam(required = false) String stops,
                                      @RequestParam(defaultValue = "false") boolean route,
                                      @RequestParam(required = false) String others,
                                      @RequestParam(defaultValue = "600") int w,
                                      @RequestParam(defaultValue = "400") int h,
                                      @RequestParam(required = false) String color,
                                      @RequestParam(defaultValue = "0") int circle,
                                      @RequestParam(required = false) String lines,
                                      @RequestParam(required = false) String center,
                                      @RequestParam(required = false) Integer zoom,
                                      @RequestParam(defaultValue = "true") boolean marks,
                                      @RequestParam(required = false) String mt) {
        mediaAuth.user(mt);
        List<double[]> c = points(center);
        byte[] png = places.staticMap(new AmapPlaceService.MapSpec(points(stops), route, points(others), w, h,
                color(color), Math.max(0, Math.min(circle, 50_000)), lines(lines), c.isEmpty() ? null : c.get(0), zoom,
                marks));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate()).body(png);
    }

    /** 颜色只收 6 位十六进制，其他的当没给。 */
    private static String color(String text) {
        return text != null && text.matches("[0-9A-Fa-f]{6}") ? text.toUpperCase() : null;
    }

    /** "颜色:经度,纬度;经度,纬度|颜色:…" 拆成几条线，最多 6 条。 */
    private static List<AmapPlaceService.Line> lines(String text) {
        List<AmapPlaceService.Line> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split("\\|");
        if (parts.length > 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "最多再画 6 条路线");
        }
        for (String part : parts) {
            int colon = part.indexOf(':');
            String c = colon < 0 ? null : color(part.substring(0, colon));
            if (c == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "路线要写成 颜色:经度,纬度;经度,纬度");
            }
            out.add(new AmapPlaceService.Line(c, points(part.substring(colon + 1))));
        }
        return out;
    }

    /** "经度,纬度;经度,纬度" 拆成坐标列表。 */
    private static List<double[]> points(String text) {
        List<double[]> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] pairs = text.split(";");
        if (pairs.length > MAX_POINTS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "地图上最多 " + MAX_POINTS + " 个点");
        }
        for (String pair : pairs) {
            String[] xy = pair.split(",");
            try {
                out.add(new double[]{Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim())});
            } catch (RuntimeException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "坐标格式要写成 经度,纬度");
            }
        }
        return out;
    }
}
