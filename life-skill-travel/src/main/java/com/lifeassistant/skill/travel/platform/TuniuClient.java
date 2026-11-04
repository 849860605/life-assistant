package com.lifeassistant.skill.travel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.CallRecorder;
import lombok.extern.slf4j.Slf4j;
import com.lifeassistant.core.integration.IntegrationSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 途牛开放平台 MCP（Streamable HTTP，无状态，不用先 initialize）：https://openapi.tuniu.cn/mcp/{业务}，
 * API Key 放 apiKey 请求头。额度很紧：每分钟 5 次、每天 50 次（所有 Key 共用），所以本地计次、结果缓存 30 分钟，
 * 快到上限就不再调用，直接告诉用户。只用查询类工具，不调下单。
 */
@Slf4j
@Component
public class TuniuClient {

    private static final String BASE = "https://openapi.tuniu.cn/mcp/";
    private static final int PER_MINUTE = 5;
    private static final int PER_DAY = 50;
    private static final long CACHE_MILLIS = 30 * 60_000L;

    private final IntegrationSettings settings;
    private final ObjectMapper objectMapper;
    private final CallRecorder recorder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Deque<Long> recent = new ArrayDeque<>();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private LocalDate day = LocalDate.now();
    private int usedToday;

    /** Key 和开关每次调用时从后台配置取（没在后台填就用 TUNIU_API_KEY）。 */
    public TuniuClient(IntegrationSettings settings, ObjectMapper objectMapper, CallRecorder recorder) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.recorder = recorder;
    }

    /** 配了 Key 且后台没停用。 */
    public boolean available() {
        return StringUtils.hasText(settings.value("tuniu", "api-key"));
    }

    /**
     * 调一个工具，返回工具结果里的 JSON（途牛把结果放在 content[0].text 里）。超额度、网络错误、途牛报错时
     * 抛 IllegalStateException，消息可以直接给用户看。
     */
    public JsonNode call(String service, String tool, Map<String, Object> arguments) {
        String key = service + "|" + tool + "|" + arguments;
        Object[] hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < CACHE_MILLIS) {
            return (JsonNode) hit[1];
        }
        take();
        JsonNode result = recorder.platform("途牛", tool + " " + arguments, () -> post(service, tool, arguments));
        if (cache.size() > 300) {
            cache.clear();
        }
        cache.put(key, new Object[]{System.currentTimeMillis(), result});
        return result;
    }

    /** 记一次调用；超过每分钟或每天的额度就拒绝。 */
    private synchronized void take() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        if (usedToday >= PER_DAY) {
            throw new IllegalStateException("途牛今天的查询额度（" + PER_DAY + " 次）用完了，明天恢复");
        }
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) {
            recent.pollFirst();
        }
        if (recent.size() >= PER_MINUTE) {
            throw new IllegalStateException("途牛每分钟最多查 " + PER_MINUTE + " 次，过一分钟再试");
        }
        recent.addLast(now);
        usedToday++;
    }

    /**
     * 后台"测试"用：列一下酒店服务的工具，返回工具个数；也算一次额度。没配置、停用或出错抛 IllegalStateException。
     */
    public int ping() {
        take();
        return recorder.platform("途牛", "tools/list", () -> {
            JsonNode message = send("hotel", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
            return message.path("result").path("tools").size();
        });
    }

    private JsonNode post(String service, String tool, Map<String, Object> arguments) {
        try {
            JsonNode message = send(service, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", tool, "arguments", arguments)));
            JsonNode content = message.path("result").path("content").path(0).path("text");
            if (message.path("result").path("isError").asBoolean(false)) {
                throw new IllegalStateException("途牛报错：" + abbreviate(content.asText()));
            }
            return content.isMissingNode() ? objectMapper.createObjectNode() : objectMapper.readTree(content.asText());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("途牛暂时查不到：" + e.getMessage());
        }
    }

    /** 发一个 JSON-RPC 请求，返回整条响应消息；HTTP 出错或途牛报错抛 IllegalStateException。 */
    private JsonNode send(String service, Map<String, Object> request) {
        String key = settings.value("tuniu", "api-key");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("途牛没有配置或已在后台停用");
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(BASE + service))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("apiKey", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("途牛返回 " + res.statusCode() + "：" + abbreviate(res.body()));
            }
            JsonNode message = parse(res.body());
            if (message.has("error")) {
                throw new IllegalStateException("途牛报错：" + message.path("error").path("message").asText());
            }
            return message;
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("途牛查询被中断");
        } catch (Exception e) {
            throw new IllegalStateException("途牛暂时查不到：" + e.getMessage());
        }
    }

    /** 响应可能是 SSE（event: message / data: {...}），也可能直接是 JSON。 */
    private JsonNode parse(String text) throws Exception {
        String json = text.strip();
        for (String line : text.split("\n")) {
            if (line.startsWith("data:")) {
                json = line.substring(5).strip();
            }
        }
        return objectMapper.readTree(json);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
