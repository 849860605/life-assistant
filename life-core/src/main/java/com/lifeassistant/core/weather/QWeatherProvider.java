package com.lifeassistant.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.integration.ByConfig;
import com.lifeassistant.core.integration.IntegrationSettings;
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
 * 和风天气。用控制台分配的 API Host；鉴权优先用 JWT（和风推荐，Ed25519 签名，Authorization: Bearer），
 * 没配 JWT 时用 API KEY（请求头 X-QW-Api-Key，2027-01-01 起和风会限制它每天的请求数）。
 * 接口路径以和风官方文档为准（dev.qweather.com），变更时只需要改这个类：
 * 预警和空气质量的 v7 接口已经停用（2026-10-01、2026-06-01），改用按经纬度查的 weatheralert/v1、airquality/v1；
 * 预报的 v7 接口用到 2027-06-01。
 */
@Slf4j
@Component
public class QWeatherProvider implements WeatherProvider {

    private final RestClient.Builder builder;
    private final IntegrationSettings settings;
    private final ByConfig<RestClient> restClient = new ByConfig<>();

    /** Host、Key 和开关每次调用时从后台配置取；没配也能正常启动，到真正调用时才报错（见 get）。 */
    public QWeatherProvider(RestClient.Builder externalRestClientBuilder, IntegrationSettings settings) {
        this.builder = externalRestClientBuilder.clone();
        this.settings = settings;
    }

    @Override
    public String name() {
        return "和风天气";
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(settings.value("qweather", "api-host")) && (jwtReady() || StringUtils.hasText(settings.value("qweather", "api-key")));
    }

    /** JWT 四项（开发者 ID、项目 ID、凭据 ID、私钥）都填了。 */
    private boolean jwtReady() {
        return java.util.stream.Stream.of("developer-id", "project-id", "key-id", "private-key")
                .allMatch(f -> StringUtils.hasText(settings.value("qweather", f)));
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

    /** 对应和风 /weatheralert/v1/current/{纬度}/{经度}（取消类的不算）；级别用预警颜色（如 orange）。 */
    @Override
    public List<WeatherWarning> warnings(WeatherLocation location) {
        JsonNode body = getV1("/weatheralert/v1/current/" + coord(location.lat()) + "/" + coord(location.lon()), true);
        List<WeatherWarning> out = new ArrayList<>();
        body.path("alerts").forEach(a -> {
            if (!"cancel".equals(a.path("messageType").path("code").asText())) {
                out.add(new WeatherWarning(a.path("headline").asText(), a.path("color").path("code").asText(a.path("severity").asText()),
                        a.path("eventType").path("name").asText(), a.path("description").asText()));
            }
        });
        return out;
    }

    /** 对应和风 /v7/weather/now。 */
    @Override
    public WeatherNow now(WeatherLocation location) {
        JsonNode n = get("/v7/weather/now", "location", location.id()).path("now");
        return new WeatherNow(n.path("text").asText(), intOrNull(n, "temp"), intOrNull(n, "feelsLike"),
                intOrNull(n, "humidity"), n.path("windDir").asText(null), n.path("windScale").asText(null),
                n.path("obsTime").asText(null));
    }

    /** 对应和风 /airquality/v1/current/{纬度}/{经度}：AQI 取中国国标（cn-mee），没有就取第一个；各污染物取浓度。 */
    @Override
    public AirQuality air(WeatherLocation location) {
        JsonNode body = getV1("/airquality/v1/current/" + coord(location.lat()) + "/" + coord(location.lon()), false);
        JsonNode index = body.path("indexes").path(0);
        for (JsonNode i : body.path("indexes")) {
            if ("cn-mee".equals(i.path("code").asText())) {
                index = i;
                break;
            }
        }
        java.util.Map<String, String> c = new java.util.HashMap<>();
        body.path("pollutants").forEach(p -> c.put(p.path("code").asText(), p.path("concentration").path("value").asText(null)));
        return new AirQuality(intOrNull(index, "aqi"), index.path("category").asText(null), c.get("pm2p5"), c.get("pm10"),
                c.get("so2"), c.get("no2"), c.get("o3"), c.get("co"));
    }

    /** 新版接口的经纬度：最多两位小数。 */
    private static String coord(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        String t = node.path(field).asText("");
        try {
            return t.isBlank() ? null : (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** v7 和 GeoAPI：带 lang=zh 并校验业务码 code=200；未配置、调用失败或返回错误码都抛 BizException。 */
    private JsonNode get(String path, String param, String value) {
        JsonNode body = call(path, b -> b.queryParam(param, value));
        if (body == null || !"200".equals(body.path("code").asText())) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE,
                    "和风天气返回错误码：" + (body == null ? "空" : body.path("code").asText()));
        }
        return body;
    }

    /** v1 接口没有 code 字段，HTTP 200 就是成功（出错时 HTTP 是 4xx/5xx，由 call 抛出）；localTime 为 true 时时间按当地。 */
    private JsonNode getV1(String path, boolean localTime) {
        JsonNode body = call(path, b -> localTime ? b.queryParam("localTime", true) : b);
        if (body == null) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "和风天气返回空");
        }
        return body;
    }

    private JsonNode call(String path, Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder> query) {
        String host = settings.value("qweather", "api-host");
        if (!StringUtils.hasText(host) || !available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置和风天气（API Host，加上 JWT 或 API Key）或已在后台停用");
        }
        RestClient client = restClient.get(host, () -> builder.clone()
                .baseUrl("https://" + host.replaceFirst("^https?://", ""))
                .build());
        try {
            return client.get()
                    .uri(b -> query.apply(b.path(path)).queryParam("lang", "zh").build())
                    .headers(h -> {
                        if (jwtReady()) {
                            h.setBearerAuth(jwt());
                        } else {
                            h.set("X-QW-Api-Key", settings.value("qweather", "api-key"));
                        }
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("和风天气调用失败 {}: {}", path, e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "和风天气调用失败", e);
        }
    }

    /** 签好的 JWT 缓存到快过期（有效期 1 小时，和风最长允许 24 小时）；配置改了就重签。 */
    private volatile String cachedJwt;
    private volatile String cachedJwtFor;
    private volatile long cachedJwtUntil;

    /**
     * 按和风文档签 JWT：Header {alg: EdDSA, kid: 凭据 ID}，Payload {iss: 开发者 ID, sub: 项目 ID, iat: 现在 - 30 秒, exp: 现在 + 1 小时}，
     * 用 Ed25519 私钥签名，三段都用 Base64URL（不带 =）。
     */
    private String jwt() {
        String iss = settings.value("qweather", "developer-id").trim();
        String sub = settings.value("qweather", "project-id").trim();
        String kid = settings.value("qweather", "key-id").trim();
        String pem = settings.value("qweather", "private-key");
        String forKey = iss + "|" + sub + "|" + kid + "|" + pem.hashCode();
        long now = System.currentTimeMillis() / 1000;
        if (cachedJwt != null && forKey.equals(cachedJwtFor) && now < cachedJwtUntil) {
            return cachedJwt;
        }
        try {
            String token = sign(iss, sub, kid, pem, now);
            cachedJwt = token;
            cachedJwtFor = forKey;
            cachedJwtUntil = now + 3000;
            return token;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "和风天气的私钥不对，要 openssl 生成的 Ed25519 私钥（PEM）");
        }
    }

    /** 签一个 now 时刻起 1 小时有效的 JWT。 */
    static String sign(String iss, String sub, String kid, String pem, long now) throws java.security.GeneralSecurityException {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(("{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"iss\":\"" + iss + "\",\"sub\":\"" + sub + "\",\"iat\":" + (now - 30)
                + ",\"exp\":" + (now + 3600) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(privateKey(pem));
        signer.update((header + "." + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return header + "." + payload + "." + b64.encodeToString(signer.sign());
    }

    /** PEM（带不带 BEGIN/END、有没有换行都行）→ Ed25519 私钥。 */
    static java.security.PrivateKey privateKey(String pem) throws java.security.GeneralSecurityException {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
        return java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(body)));
    }

    /** 把 JSON 数组逐个转换成列表。 */
    private static <T> List<T> map(JsonNode array, Function<JsonNode, T> mapper) {
        List<T> list = new ArrayList<>();
        array.forEach(node -> list.add(mapper.apply(node)));
        return list;
    }
}
