package com.lifeassistant.core.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.integration.IntegrationSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 美团酒旅 ht-ai（美团官方给 AI 助手的查询接口，Token 在 developer.meituan.com 申请）。
 * 请求格式取自官方命令行工具 @meituan-travel/ht-ai：Authorization 头直接放 Token，返回 {result, data(Markdown)}。
 * 酒店、机票、火车票、美食（大众点评的店铺和评分）都能查。
 */
@Component
public class MeituanHtClient {

    private final RestClient restClient;
    private final IntegrationSettings settings;
    private final CallRecorder recorder;

    /** Token 和开关每次调用时从后台配置取（没在后台填就用 MEITUAN_HT_TOKEN）。 */
    public MeituanHtClient(IntegrationSettings settings, CallRecorder recorder) {
        this.settings = settings;
        this.recorder = recorder;
        this.restClient = RestClient.builder()
                .baseUrl("https://mcp-open-cater.meituan.com")
                .requestFactory(HttpTimeouts.factory(Duration.ofSeconds(90)))
                .build();
    }

    /** 配了 Token 且后台没停用。 */
    public boolean available() {
        return StringUtils.hasText(settings.value("meituan", "token"));
    }

    /** 提一个问题，成功返回 Markdown；接口返回失败时抛 IllegalStateException，网络错误抛 RestClientException。 */
    public String query(String city, String question, String originQuery) {
        return recorder.platform("美团", question, () -> doQuery(city, question, originQuery));
    }

    private String doQuery(String city, String question, String originQuery) {
        String token = settings.value("meituan", "token");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("美团没有配置或已在后台停用");
        }
        Map<String, String> body = Map.of(
                "city", city == null ? "" : city,
                "query", question,
                "originQuery", originQuery,
                "channel", "default");
        JsonNode root = restClient.post().uri("/v1/api/voyage/openapi/query")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        if (root == null || !"SUCCESS".equals(root.path("result").asText())) {
            throw new IllegalStateException("美团返回失败：" + (root == null ? "空" : root.path("msg").asText(root.toString())));
        }
        return root.path("data").asText();
    }
}
