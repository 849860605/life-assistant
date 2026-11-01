package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.MediaTicket;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.place.AmapPlaceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 页面上的可拖动地图（高德 JS API 2.0）：给前端 JS Key，转发高德 JS API 的服务请求并在后端加上安全密钥
 * （高德推荐的代理方式，安全密钥不出现在前端），以及给地图画线用的路线坐标。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/maps")
@RequiredArgsConstructor
public class AmapJsController {

    private static final String PROXY_PREFIX = "/api/v1/maps/amap/";
    /** 只转发高德服务的路径，防止被拿来访问别的地址 */
    private static final Pattern SAFE_PATH = Pattern.compile("^/v[345]/[A-Za-z0-9/_.\\-]{1,200}$");
    private static final int MAX_STOPS = 15;

    private final LifeProperties properties;
    private final MediaTicket mediaTicket;
    private final AmapPlaceService places;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** GET /api/v1/maps/js-config：JS Key（没配为空，页面就用静态地图）。 */
    @GetMapping("/js-config")
    public ApiResponse<Map<String, Object>> jsConfig() {
        LifeProperties.Amap amap = properties.getAmap();
        boolean on = StringUtils.hasText(amap.getJsKey()) && StringUtils.hasText(amap.getJsSecurity());
        return ApiResponse.ok(Map.of("key", on ? amap.getJsKey() : ""));
    }

    /**
     * GET /api/v1/maps/path?stops=经度,纬度;经度,纬度：相邻两点之间的路线坐标（近的直线、远的高德驾车路线），
     * 每段一个坐标数组。
     */
    @GetMapping("/path")
    public ApiResponse<List<List<double[]>>> path(@RequestParam String stops) {
        List<double[]> pts = new ArrayList<>();
        for (String p : stops.split(";")) {
            String[] xy = p.split(",");
            if (xy.length == 2) {
                try {
                    pts.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                } catch (NumberFormatException ignored) {
                    // 跳过写错的点
                }
            }
            if (pts.size() >= MAX_STOPS) {
                break;
            }
        }
        List<List<double[]>> out = new ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            out.add(places.path(pts.get(i), pts.get(i + 1)));
        }
        return ApiResponse.ok(out);
    }

    /**
     * GET /api/v1/maps/amap/{下载票据}/v3/...：高德 JS API 的 serviceHost，按高德文档的代理规则转发，查询串后面加 jscode。
     * 地图脚本发的请求带不了登录 token，所以把下载票据放在路径里校验。
     */
    @GetMapping("/amap/{mt}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String mt, HttpServletRequest request) {
        if (mediaTicket.verify(mt).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String security = properties.getAmap().getJsSecurity();
        if (!StringUtils.hasText(security)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String uri = request.getRequestURI();
        int at = uri.indexOf(PROXY_PREFIX + mt);
        String path = at < 0 ? "" : uri.substring(at + PROXY_PREFIX.length() + mt.length());
        if (!SAFE_PATH.matcher(path).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        // 高德文档：样式走 webapi，矢量数据走 fmap01，其余走 restapi
        String host = path.startsWith("/v4/map/styles") ? "https://webapi.amap.com"
                : path.startsWith("/v3/vectormap") ? "https://fmap01.amap.com" : "https://restapi.amap.com";
        String query = request.getQueryString();
        String target = host + path + "?" + (query == null ? "" : query + "&") + "jscode="
                + URLEncoder.encode(security, StandardCharsets.UTF_8);
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", request.getHeader("User-Agent") == null ? "Mozilla/5.0" : request.getHeader("User-Agent"))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String type = res.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE);
            return ResponseEntity.status(res.statusCode()).contentType(MediaType.parseMediaType(type)).body(res.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("转发高德请求失败 {}：{}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
