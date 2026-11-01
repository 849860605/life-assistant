package com.lifeassistant.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 高德天气，没配和风时兜底。只有 4 天的逐日预报（天气、温度、风力），
 * 没有降水量、逐小时和预警；日出日落用 {@link SunCalculator} 按坐标算。
 */
@Slf4j
@Component
public class AmapWeatherProvider implements WeatherProvider {

    /** 高德只覆盖国内，日出日落按北京时间算 */
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final RestClient restClient;
    private final String key;

    public AmapWeatherProvider(RestClient.Builder externalRestClientBuilder, LifeProperties properties) {
        this.key = properties.getAmap().getKey();
        this.restClient = externalRestClientBuilder.clone().baseUrl("https://restapi.amap.com").build();
    }

    @Override
    public String name() {
        return "高德天气（没有降水量、逐小时预报和气象预警；日出日落按经纬度计算）";
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(key);
    }

    /** 坐标走逆地理编码取区县编码；地名先地理编码，查不到再按关键字搜地点（景区名常常要走这一步）。 */
    @Override
    public WeatherLocation resolve(String query) {
        String text = query.trim();
        if (text.matches("-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?")) {
            String[] parts = text.split(",");
            double lon = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            JsonNode address = get("/v3/geocode/regeo", "location", String.format(Locale.ROOT, "%.6f,%.6f", lon, lat))
                    .path("regeocode").path("addressComponent");
            return new WeatherLocation(address.path("adcode").asText(), text, address.path("province").asText(), lon, lat);
        }
        JsonNode geo = get("/v3/geocode/geo", "address", text).path("geocodes").path(0);
        if (!geo.isMissingNode() && StringUtils.hasText(geo.path("adcode").asText())) {
            return location(text, geo.path("adcode").asText(), geo.path("province").asText(), geo.path("location").asText());
        }
        JsonNode poi = get("/v3/place/text", "keywords", text).path("pois").path(0);
        if (poi.isMissingNode()) {
            throw new BizException(ErrorCode.NOT_FOUND, "找不到地点：" + query);
        }
        return location(poi.path("name").asText(text), poi.path("adcode").asText(), poi.path("pname").asText(),
                poi.path("location").asText());
    }

    /** 对应高德 /v3/weather/weatherInfo?extensions=all，最多 4 天；降水量填 0 表示未知。 */
    @Override
    public List<DailyForecast> daily(WeatherLocation location, int days) {
        JsonNode casts = get("/v3/weather/weatherInfo", "city", location.id(), "extensions", "all")
                .path("forecasts").path(0).path("casts");
        List<DailyForecast> result = new ArrayList<>();
        for (JsonNode c : casts) {
            if (result.size() >= days) {
                break;
            }
            LocalDate date = LocalDate.parse(c.path("date").asText());
            SunCalculator.SunTimes sun = SunCalculator.of(date, location.lat(), location.lon(), CHINA).orElse(null);
            result.add(new DailyForecast(date.toString(), c.path("dayweather").asText(), c.path("nightweather").asText(),
                    c.path("nighttemp").asInt(), c.path("daytemp").asInt(), c.path("daypower").asText(), 0, "",
                    sun == null ? "" : sun.sunrise().toString(), sun == null ? "" : sun.sunset().toString()));
        }
        return result;
    }

    /** 对应高德 /v3/weather/weatherInfo?extensions=base（实况，没有体感温度）。 */
    @Override
    public WeatherNow now(WeatherLocation location) {
        JsonNode live = get("/v3/weather/weatherInfo", "city", location.id(), "extensions", "base")
                .path("lives").path(0);
        Integer temp = live.path("temperature").asText("").matches("-?\\d+(\\.\\d+)?")
                ? (int) Math.round(live.path("temperature").asDouble()) : null;
        Integer humidity = live.path("humidity").asText("").matches("\\d+(\\.\\d+)?")
                ? (int) Math.round(live.path("humidity").asDouble()) : null;
        String dir = live.path("winddirection").asText("");
        return new WeatherNow(live.path("weather").asText(), temp, null, humidity,
                dir.isBlank() ? null : dir + (dir.endsWith("风") ? "" : "风"), live.path("windpower").asText(null),
                live.path("reporttime").asText(null));
    }

    /**
     * 同省离这里最近的几个城市（高德行政区划接口，带城市中心坐标和区划编码，可以直接查天气）。
     */
    public List<WeatherLocation> nearbyCities(WeatherLocation around, int count) {
        if (around.adm() == null || around.adm().isBlank()) {
            return List.of();
        }
        JsonNode cities = get("/v3/config/district", "keywords", around.adm(), "subdistrict", "1")
                .path("districts").path(0).path("districts");
        List<WeatherLocation> out = new ArrayList<>();
        for (JsonNode c : cities) {
            String center = c.path("center").asText("");
            if (!center.contains(",")) {
                continue;
            }
            WeatherLocation loc = location(c.path("name").asText(), c.path("adcode").asText(), around.adm(), center);
            if (Math.abs(loc.lon() - around.lon()) < 0.05 && Math.abs(loc.lat() - around.lat()) < 0.05) {
                continue;
            }
            out.add(loc);
        }
        out.sort(java.util.Comparator.comparingDouble(l -> Math.hypot(l.lon() - around.lon(), l.lat() - around.lat())));
        return out.subList(0, Math.min(count, out.size()));
    }

    @Override
    public List<HourlyForecast> hourly(WeatherLocation location) {
        throw new BizException(ErrorCode.EXTERNAL_SERVICE, "高德没有逐小时预报，配置和风天气后才能查");
    }

    @Override
    public List<WeatherWarning> warnings(WeatherLocation location) {
        throw new BizException(ErrorCode.EXTERNAL_SERVICE, "高德没有气象预警，配置和风天气后才能查");
    }

    /** 把 "经度,纬度" 字符串拆开构造位置。 */
    private static WeatherLocation location(String name, String adcode, String province, String lonLat) {
        String[] parts = lonLat.split(",");
        return new WeatherLocation(adcode, name, province, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }

    /** 统一 GET：带 key，校验 status=1；未配置、调用失败或返回错误都抛 BizException。 */
    private JsonNode get(String path, String... params) {
        if (!available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置高德 Key（AMAP_KEY）");
        }
        try {
            JsonNode body = restClient.get().uri(b -> {
                b.path(path).queryParam("key", key);
                for (int i = 0; i + 1 < params.length; i += 2) {
                    b.queryParam(params[i], params[i + 1]);
                }
                return b.build();
            }).retrieve().body(JsonNode.class);
            if (body == null || !"1".equals(body.path("status").asText())) {
                throw new BizException(ErrorCode.EXTERNAL_SERVICE,
                        "高德返回错误：" + (body == null ? "空" : body.path("info").asText()));
            }
            return body;
        } catch (RestClientException e) {
            log.warn("调用高德 {} 失败：{}", path, e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "高德天气暂时不可用", e);
        }
    }
}
