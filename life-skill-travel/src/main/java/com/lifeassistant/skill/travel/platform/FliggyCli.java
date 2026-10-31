package com.lifeassistant.skill.travel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 飞猪 FlyAI（阿里官方，Key 在 flyai.open.fliggy.com 申请）。官方只提供命令行工具 @fly-ai/flyai-cli，
 * 请求签名在工具里实现，所以直接调用官方工具，不自己拼请求。酒店、机票、火车票都能查，输出 JSON。
 * 部署时需要 Node.js 和这个工具，路径用 FLYAI_COMMAND 配置。
 */
@Component
public class FliggyCli {

    private static final long TIMEOUT_SECONDS = 30;

    private final String apiKey;
    private final List<String> command;
    private final ObjectMapper objectMapper;
    private final CallRecorder recorder;

    public FliggyCli(@Value("${life.hotel.fliggy.api-key:}") String apiKey,
                     @Value("${life.hotel.fliggy.command:flyai}") String command, ObjectMapper objectMapper,
                     CallRecorder recorder) {
        this.apiKey = apiKey;
        this.command = Arrays.asList(command.trim().split("\\s+"));
        this.objectMapper = objectMapper;
        this.recorder = recorder;
    }

    public boolean available() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * 执行一条子命令（如 search-hotel、search-flight）并解析输出的 JSON。
     * 工具没装好、超时或输出不是 JSON 时抛 IllegalStateException，消息可以直接给用户看。
     */
    public JsonNode run(List<String> args) {
        return recorder.platform("飞猪", String.join(" ", args), () -> doRun(args));
    }

    private JsonNode doRun(List<String> args) {
        List<String> full = new ArrayList<>(command);
        full.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(full).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("FLYAI_API_KEY", apiKey);
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("飞猪查询超时");
            }
            return objectMapper.readTree(new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("飞猪工具没装好或调用失败（检查 FLYAI_COMMAND）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞猪查询被中断", e);
        }
    }
}
