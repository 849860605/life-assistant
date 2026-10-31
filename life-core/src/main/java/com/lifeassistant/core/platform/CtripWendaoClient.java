package com.lifeassistant.core.platform;

import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.beans.factory.annotation.Value;
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
    private final String token;
    private final CallRecorder recorder;

    public CtripWendaoClient(@Value("${life.hotel.ctrip.token:}") String token, CallRecorder recorder) {
        this.token = token;
        this.recorder = recorder;
        this.restClient = RestClient.builder()
                .baseUrl("https://wendao-skill-prod.ctrip.com")
                .requestFactory(HttpTimeouts.factory(Duration.ofSeconds(60)))
                .build();
    }

    /** 提一个问题，返回 Markdown 原文；网络或接口错误抛 RestClientException。 */
    public String query(String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            body.put("token", token);
        }
        body.put("query", question);
        body.put("source", "github");
        return recorder.platform("携程", question, () -> restClient.post().uri("/skill/query")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }
}
