package com.lifeassistant.core.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 看图模型（life.models.vision，默认通义千问 VL）：把截图、照片、扫描件转写成文字。
 * 走各家的 OpenAI 兼容接口，百炼用 compatible-mode；证件号、手机号让模型打码，不写进对话。
 */
@Slf4j
@Component
public class VisionReader {

    static final String PROMPT = """
            把图片里的文字和信息完整、准确地转写出来，保持原来的结构，表格用 | 分隔列。
            如果是机票行程单、酒店订单、火车票、门票、预约凭证这类单据，第一行写「类型：xxx」，
            再逐项列出：订单号、航班号或车次、日期、起飞/出发和到达时间、出发和到达的机场或车站（含航站楼）、
            航空公司、酒店名称和地址、入住和离店日期、房型、间数、人数、金额、取消规则。
            姓名只保留姓，身份证号、护照号、手机号、银行卡号用 * 打码，不要写出完整号码。
            看不清的写「看不清」，不要猜；不要加解释，只输出转写内容。""";

    private final ChatModelRegistry models;
    private final LifeProperties properties;
    private final UsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;
    private final RestClient http;

    public VisionReader(ChatModelRegistry models, LifeProperties properties, UsageRecorder usageRecorder,
                        ObjectMapper objectMapper) {
        this.models = models;
        this.properties = properties;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** 一张图或几页扫描件（同一个文件）一起识别，返回转写的文字。images 是 JPEG/PNG 等的原始字节和类型。 */
    public String read(List<Image> images, Long userId) {
        ModelSpec spec = models.resolve(properties.getModels().getVision());
        LifeProperties.Provider provider = properties.getLlm().getProviders().get(spec.provider());
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "看图模型没有配置 Key，识别不了图片");
        }
        List<Object> content = new ArrayList<>();
        for (Image image : images) {
            String url = "data:" + image.mime() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        content.add(Map.of("type", "text", "text", PROMPT));
        Map<String, Object> body = Map.of("model", spec.model(),
                "messages", List.of(Map.of("role", "user", "content", content)));
        try {
            String raw = http.post().uri(endpoint(provider)).header("Authorization", "Bearer " + provider.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
            JsonNode json = objectMapper.readTree(raw);
            JsonNode usage = json.path("usage");
            usageRecorder.record(userId, null, "VISION", spec.model(),
                    new DefaultUsage(usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt()));
            return json.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("看图模型识别失败：{}", e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "图片识别服务这会儿不可用，稍后再试");
        }
    }

    /** 兼容接口地址：百炼走 compatible-mode，其他按配置的 base-url 和 completions-path。 */
    private static String endpoint(LifeProperties.Provider provider) {
        if ("dashscope".equals(provider.getType())) {
            String base = provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                    ? "https://dashscope.aliyuncs.com" : provider.getBaseUrl();
            return base + "/compatible-mode/v1/chat/completions";
        }
        String path = provider.getCompletionsPath() == null ? "/v1/chat/completions" : provider.getCompletionsPath();
        return provider.getBaseUrl() + path;
    }

    /** 一张图：原始字节和 MIME 类型。 */
    public record Image(byte[] bytes, String mime) {
    }
}
