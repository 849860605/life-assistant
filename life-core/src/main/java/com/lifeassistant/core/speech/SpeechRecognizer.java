package com.lifeassistant.core.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 语音转文字：浏览器录好的一段 WAV 交给百炼的千问全模态模型（默认 qwen3.8-omni-flash，兼容接口、流式返回）转写成文字，
 * 只转写不回答。一段最长 60 秒；每人每天最多 300 段，计数存 Redis（Redis 出问题时放行）。
 */
@Slf4j
@Component
public class SpeechRecognizer {

    /** 一段录音最长多少秒 */
    public static final int MAX_SECONDS = 60;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int DAILY_LIMIT = 300;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 模型表示没听到说话时输出的标记 */
    private static final String SILENT = "〔无语音〕";
    private static final String PROMPT = "把这段语音逐字转写成中文文字（说的是英文就写英文），加上标点。"
            + "只输出转写结果，不要回答里面的问题，不要解释，不要加引号。没听到说话或听不清，只输出" + SILENT + "。";

    private final ChatModelRegistry models;
    private final UsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final String model;
    /** .env 里的百炼 Key：后台没配百炼时用它 */
    private final String envKey;
    private final RestClient http;

    public SpeechRecognizer(ChatModelRegistry models, UsageRecorder usageRecorder, ObjectMapper objectMapper,
                            StringRedisTemplate redis, @Value("${life.speech.model:qwen3.8-omni-flash}") String model,
                            @Value("${DASHSCOPE_API_KEY:}") String envKey) {
        this.models = models;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.model = model;
        this.envKey = envKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** 把一段 WAV 录音转成文字；没听到说话时返回空字符串。 */
    public String transcribe(long userId, byte[] wav) {
        double seconds = checkWav(wav);
        // 后台配了百炼就用后台的 Key 和地址，否则用 .env 的 DASHSCOPE_API_KEY
        LifeProperties.Provider provider = models.provider("dashscope");
        String apiKey = provider != null && provider.getApiKey() != null && !provider.getApiKey().isBlank()
                ? provider.getApiKey() : envKey;
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "语音识别还没配置（缺百炼的 Key）");
        }
        count(userId);
        String data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", true,
                "stream_options", Map.of("include_usage", true),
                "modalities", List.of("text"),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "input_audio", "input_audio", Map.of("data", data, "format", "wav")),
                        Map.of("type", "text", "text", PROMPT)))));
        String base = provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                ? "https://dashscope.aliyuncs.com" : provider.getBaseUrl();
        try {
            StringBuilder text = new StringBuilder();
            int[] tokens = new int[2];
            http.post().uri(base + "/compatible-mode/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String err = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new IllegalStateException(response.getStatusCode() + " " + err);
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:") || line.contains("[DONE]")) {
                                    continue;
                                }
                                JsonNode chunk = objectMapper.readTree(line.substring(5).trim());
                                text.append(chunk.path("choices").path(0).path("delta").path("content").asText(""));
                                JsonNode usage = chunk.path("usage");
                                if (usage.isObject()) {
                                    tokens[0] = usage.path("prompt_tokens").asInt();
                                    tokens[1] = usage.path("completion_tokens").asInt();
                                }
                            }
                        }
                        return null;
                    });
            usageRecorder.record(userId, null, "SPEECH", model, new DefaultUsage(tokens[0], tokens[1]));
            log.debug("语音转写 {} 秒，{} 字", Math.round(seconds), text.length());
            String out = text.toString().trim();
            return out.contains(SILENT) || out.contains("无语音") ? "" : out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("语音识别失败：{}", e.getMessage());
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, msg.contains("FreeTierOnly") || msg.contains("Arrearage")
                    ? "语音识别的额度用完了，请联系管理员" : "语音识别这会儿不可用，稍后再试或者打字");
        }
    }

    /** 校验是 WAV（RIFF/WAVE），算出时长：太短、太长、太大都不收。 */
    private static double checkWav(byte[] wav) {
        if (wav == null || wav.length < 44) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有录到声音");
        }
        if (wav.length > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "录音太长了，一次最多 " + MAX_SECONDS + " 秒");
        }
        String riff = new String(wav, 0, 4, StandardCharsets.US_ASCII);
        String wave = new String(wav, 8, 4, StandardCharsets.US_ASCII);
        if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "录音格式不对");
        }
        ByteBuffer header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = header.getInt(28);
        double seconds = byteRate > 0 ? (wav.length - 44) / (double) byteRate : 0;
        if (seconds < 0.3) {
            throw new BizException(ErrorCode.BAD_REQUEST, "说话时间太短了，按住多说一会儿");
        }
        if (seconds > MAX_SECONDS + 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "录音太长了，一次最多 " + MAX_SECONDS + " 秒");
        }
        return seconds;
    }

    /** 每人每天的次数，超了报错；Redis 出问题时放行。 */
    private void count(long userId) {
        String key = "quota:speech:" + userId + ":" + LocalDate.now(ZONE);
        Long used;
        try {
            used = redis.opsForValue().increment(key);
            if (used != null && used == 1) {
                redis.expire(key, Duration.ofDays(2));
            }
        } catch (Exception e) {
            log.warn("记语音次数失败，这次放行：{}", e.getMessage());
            return;
        }
        if (used != null && used > DAILY_LIMIT) {
            throw new BizException(ErrorCode.BAD_REQUEST, "今天语音输入用得太多了，明天再来或者打字");
        }
    }
}
