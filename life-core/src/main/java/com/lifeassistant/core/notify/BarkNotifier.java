package com.lifeassistant.core.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

/**
 * Bark 推送（iPhone）。配置 JSON：{"deviceKey": "..."}。
 * <p>
 * 通知内容会经过 Bark 服务器。健康类提醒只写概要，详细内容点进页面看；
 * 也可以在 life.bark.server 配置自建的 bark-server。加密推送是下一步要做的。
 */
@Component
public class BarkNotifier implements Notifier {

    public static final String TYPE = "BARK";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 服务地址取 life.bark.server，可指向自建的 bark-server。 */
    public BarkNotifier(RestClient.Builder externalRestClientBuilder, LifeProperties properties, ObjectMapper objectMapper) {
        this.restClient = externalRestClientBuilder.clone().baseUrl(properties.getBark().getServer()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** 调 Bark 的 /push 接口；配置里缺 deviceKey、HTTP 调用失败或 Bark 返回非 200 都抛 BizException。 */
    @Override
    public void send(String channelConfig, Notice notice) {
        String deviceKey;
        try {
            deviceKey = objectMapper.readTree(channelConfig).path("deviceKey").asText(null);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Bark 配置格式不对");
        }
        if (deviceKey == null || deviceKey.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "还没有设置 Bark 设备 key");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("device_key", deviceKey);
        body.put("title", notice.title());
        body.put("body", notice.body() == null ? "" : notice.body());
        if (notice.group() != null) {
            body.put("group", notice.group());
        }
        if (notice.url() != null) {
            body.put("url", notice.url());
        }
        if (notice.level() != null) {
            body.put("level", notice.level());
        }
        try {
            JsonNode result = restClient.post().uri("/push").body(body).retrieve().body(JsonNode.class);
            if (result != null && result.path("code").asInt(200) != 200) {
                throw new BizException(ErrorCode.EXTERNAL_SERVICE, "Bark 返回错误：" + result.path("message").asText());
            }
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "Bark 推送失败：" + e.getMessage(), e);
        }
    }
}
