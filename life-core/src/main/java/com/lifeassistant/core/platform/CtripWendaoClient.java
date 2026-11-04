package com.lifeassistant.core.platform;

import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.integration.IntegrationSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 携程问道（TripAI，携程官方给 AI 助手的查询接口，Key 在 https://www.ctrip.com/wendao/openclaw 申请）。
 * 自然语言提问，返回 Markdown；酒店、机票、火车票、美食都能查。不配 Key 也能低频匿名调用。
 */
@Component
public class CtripWendaoClient {

    private final RestClient restClient;
    private final IntegrationSettings settings;
    private final CallRecorder recorder;

    /** Token 和开关每次调用时从后台配置取（没在后台填就用 TRIPAI_API_KEY）。 */
    public CtripWendaoClient(IntegrationSettings settings, CallRecorder recorder) {
        this.settings = settings;
        this.recorder = recorder;
        this.restClient = RestClient.builder()
                .baseUrl("https://wendao-skill-prod.ctrip.com")
                .requestFactory(HttpTimeouts.factory(Duration.ofSeconds(60)))
                .build();
    }

    /** 后台没有停用携程（不填 Token 也能匿名用）。 */
    public boolean available() {
        return settings.enabled("ctrip");
    }

    /** 提一个问题，返回 Markdown 原文；网络或接口错误抛 RestClientException，后台停用了抛 IllegalStateException。 */
    public String query(String question) {
        if (!available()) {
            throw new IllegalStateException("携程问道已在后台停用");
        }
        String token = settings.value("ctrip", "token");
        Map<String, Object> body = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            body.put("token", token);
        }
        body.put("query", question);
        body.put("source", "github");
        String answer = recorder.platform("携程", question, () -> restClient.post().uri("/skill/query")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        // 出错时返回的是 {"error":"..."}，不是 Markdown
        if (answer != null && answer.stripLeading().startsWith("{\"error\"")) {
            throw new IllegalStateException(answer.contains("limit exceeded")
                    ? "携程问道今天的查询额度用完了，明天恢复" : "携程问道返回错误：" + answer.strip());
        }
        return answer;
    }
}
