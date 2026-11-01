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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 和风天气。用控制台分配的 API Host + 请求头 X-QW-Api-Key 鉴权。
 * 接口路径以和风官方文档为准，变更时只需要改这个类。
 */
@Slf4j
@Component
public class QWeatherProvider implements WeatherProvider {

    private final RestClient restClient;
    private final boolean configured;

    /** 没配 Host 或 Key 也能正常启动，到真正调用时才报错（见 get）。 */
    public QWeatherProvider(RestClient.Builder externalRestClientBuilder, LifeProperties properties) {
        LifeProperties.QWeather config = properties.getQweather();
        this.configured = StringUtils.hasText(config.getApiHost()) && StringUtils.hasText(config.getApiKey());
        String host = configured ? config.getApiHost().replaceFirst("^https?://", "") : "localhost";
        this.restClient = externalRestClientBuilder.clone()
                .baseUrl("https://" + host)
                .defaultHeader("X-QW-Api-Key", configured ? config.getApiKey() : "")
                .build();
    }

    @Override
    public String name() {
        return "和风天气";
    }

    @Override
    public boolean available() {
        return configured;
    }

    /** "经度,纬度" 直接构造位置（保留两位小数作为 id），否则调城市搜索取第一条结果。 */
    @Override
    public WeatherLocation resolve(String query) {
        if (query.matches("\\s*-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?\\s*")) {
            String[] parts = query.split(",");
            String coord = String.format(Locale.ROOT, "%.2f,%.2f", Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            return new WeatherLocation(coord, coord, "", Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        }
        JsonNode body = get("/geo/v2/city/lookup", "location", query);
        JsonNode first = body.path("location").path(0);
        if (first.isMissingNode()) {
            throw new BizException(ErrorCode.NOT_FOUND, "找不到地点：" + query);
        }
        return new WeatherLocation(first.path("id").asText(), first.path("name").asText(), first.path("adm1").asText(),
                first.path("lon").asDouble(), first.path("lat").asDouble());
    }

    /** 3 天以内用 3d 接口，否则用 7d 接口，再截取前 days 天。 */
    @Override
    public List<DailyForecast> daily(WeatherLocation location, int days) {
        String path = days <= 3 ? "/v7/weather/3d" : "/v7/weather/7d";
        JsonNode body = get(path, "location", location.id());
        List<DailyForecast> result = map(body.path("daily"), d -> new DailyForecast(
                d.path("fxDate").asText(), d.path("textDay").asText(), d.path("textNight").asText(),
                d.path("tempMin").asInt(), d.path("tempMax").asInt(), d.path("windScaleDay").asText(),
                d.path("precip").asDouble(), d.path("uvIndex").asText(),
                d.path("sunrise").asText(), d.path("sunset").asText()));
        return result.subList(0, Math.min(days, result.size()));
    }

    /** 对应和风 /v7/weather/24h。 */
    @Override
    public List<HourlyForecast> hourly(WeatherLocation location) {
        JsonNode body = get("/v7/weather/24h", "location", location.id());
        return map(body.path("hourly"), h -> new HourlyForecast(
                h.path("fxTime").asText(), h.path("text").asText(), h.path("temp").asInt(),
                h.path("windScale").asText(), h.path("pop").asInt(), h.path("precip").asDouble()));
    }

    /** 对应和风 /v7/warning/now；级别优先取 severityColor，没有时退回 level。 */
    @Override
    public List<WeatherWarning> warnings(WeatherLocation location) {
        JsonNode body = get("/v7/warning/now", "location", location.id());
        return map(body.path("warning"), w -> new WeatherWarning(
                w.path("title").asText(), w.path("severityColor").asText(w.path("level").asText()),
                w.path("typeName").asText(), w.path("text").asText()));
    }

    /** 对应和风 /v7/weather/now。 */
    @Override
    public WeatherNow now(WeatherLocation location) {
        JsonNode n = get("/v7/weather/now", "location", location.id()).path("now");
        return new WeatherNow(n.path("text").asText(), intOrNull(n, "temp"), intOrNull(n, "feelsLike"),
                intOrNull(n, "humidity"), n.path("windDir").asText(null), n.path("windScale").asText(null),
                n.path("obsTime").asText(null));
    }

    /** 对应和风 /v7/air/now。 */
    @Override
    public AirQuality air(WeatherLocation location) {
        JsonNode n = get("/v7/air/now", "location", location.id()).path("now");
        return new AirQuality(intOrNull(n, "aqi"), n.path("category").asText(null), n.path("pm2p5").asText(null),
                n.path("pm10").asText(null), n.path("so2").asText(null), n.path("no2").asText(null),
                n.path("o3").asText(null), n.path("co").asText(null));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        String t = node.path(field).asText("");
        try {
            return t.isBlank() ? null : (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 统一 GET 调用：带 lang=zh 并校验业务码 code=200；未配置、调用失败或返回错误码都抛 BizException。 */
    private JsonNode get(String path, String param, String value) {
        if (!configured) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置和风天气（QWEATHER_API_HOST / QWEATHER_API_KEY）");
        }
        try {
            JsonNode body = restClient.get()
                    .uri(b -> b.path(path).queryParam(param, value).queryParam("lang", "zh").build())
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !"200".equals(body.path("code").asText())) {
                throw new BizException(ErrorCode.EXTERNAL_SERVICE,
                        "和风天气返回错误码：" + (body == null ? "空" : body.path("code").asText()));
            }
            return body;
        } catch (RestClientException e) {
            log.warn("和风天气调用失败 {}: {}", path, e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "和风天气调用失败", e);
        }
    }

    /** 把 JSON 数组逐个转换成列表。 */
    private static <T> List<T> map(JsonNode array, Function<JsonNode, T> mapper) {
        List<T> list = new ArrayList<>();
        array.forEach(node -> list.add(mapper.apply(node)));
        return list;
    }
}
