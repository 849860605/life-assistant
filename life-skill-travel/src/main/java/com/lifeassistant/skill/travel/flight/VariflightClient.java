package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 飞常准（VariFlight）航空数据：走它的 MCP 接口（JSON-RPC over HTTP），Key 放在 X-API-Key 请求头。
 * 按次计费（查航班 ¥0.5、查票价 ¥0.25），失败不计费。返回的是 Python dict 的文字，这里转成 JSON。
 */
@Component
public class VariflightClient {

    private static final String URL = "https://ai.variflight.com/servers/aviation/mcp";

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final CallRecorder recorder;
    private final RestClient http;

    public VariflightClient(@Value("${life.variflight.api-key:}") String apiKey, ObjectMapper objectMapper,
                            CallRecorder recorder) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.recorder = recorder;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(35));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** 配了 Key 才能用。 */
    public boolean available() {
        return StringUtils.hasText(apiKey);
    }

    /** 调一个工具，返回 data 部分（JSON）；接口报错、超时、解析失败抛 IllegalStateException，消息可以给用户看。 */
    public JsonNode call(String tool, Map<String, Object> args) {
        if (!available()) {
            throw new IllegalStateException("飞常准没有配置 Key");
        }
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args));
        String raw = recorder.platform("飞常准", tool + " " + args, () -> http.post().uri(URL)
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .body(body).retrieve().body(String.class));
        try {
            String json = raw != null && raw.stripLeading().startsWith("event:") || raw != null && raw.stripLeading().startsWith("data:")
                    ? String.join("", raw.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5)).toList()) : raw;
            JsonNode result = objectMapper.readTree(json).path("result");
            String text = result.path("content").path(0).path("text").asText("");
            if (result.path("isError").asBoolean(false)) {
                throw new IllegalStateException("飞常准查询出错：" + text);
            }
            int start = text.indexOf('{');
            if (start < 0) {
                throw new IllegalStateException("飞常准没有返回数据");
            }
            JsonNode data = objectMapper.readTree(PyLiteral.toJson(text.substring(start)));
            if (data.has("code") && data.path("code").asInt(200) != 200) {
                throw new IllegalStateException("飞常准：" + data.path("message").asText("查询失败"));
            }
            return data.path("data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("飞常准返回的内容看不懂：" + e.getMessage(), e);
        }
    }
}
