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
    /** 同时跑太多飞猪命令会被限流（返回"搜索…失败"或空结果），全局最多同时 2 个 */
    private static final int MAX_PARALLEL = 2;
    private static final long RETRY_DELAY_MILLIS = 600;

    private final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(MAX_PARALLEL, true);

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
        JsonNode result = limited(args);
        if (failed(result)) {
            // 多半是被限流，等一下再试一次
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = limited(args);
        }
        return result;
    }

    private JsonNode limited(List<String> args) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞猪查询被中断", e);
        }
        try {
            return recorder.platform("飞猪", String.join(" ", args), () -> doRun(args));
        } finally {
            gate.release();
        }
    }

    /** 飞猪返回失败（status 不是 0、data 为空）或者列表是空的。 */
    private static boolean failed(JsonNode root) {
        if (root == null) {
            return true;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return true;
        }
        JsonNode list = data.path("itemList");
        return list.isArray() && list.isEmpty();
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
