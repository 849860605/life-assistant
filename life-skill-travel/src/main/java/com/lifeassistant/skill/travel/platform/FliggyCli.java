package com.lifeassistant.skill.travel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.integration.IntegrationSettings;
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
    /**
     * 飞猪的限制（官方文档没写，来自 GitHub alibaba-flyai/flyai-skill 的 issue 和实测）：
     * 调用太频繁会被风控（HTTP 451/403），社区实测 QPS 上限约 2；每次调用都扣额度（首赠 5000 次，用完每天 100 次）。
     * "搜索机票低价行业失败"（status=1）是飞猪机票后端的已知问题：部分航线、日期会持续失败（国际/跨境更多），
     * 官方 2026-11-02 已确认复现、说下个版本修（issue #69、#74）。我们这边只能少调、错开、失败了等一会儿再试一次。
     * 按记录，每分钟 5 次以内失败约 4%，20 次以上约 36%，所以全局最多同时 2 个、两次开始之间至少隔 0.5 秒。
     */
    private static final int MAX_PARALLEL = 2;
    private static final long MIN_GAP_MILLIS = 500;
    private static final long RETRY_DELAY_MILLIS = 2000;
    /** 上一次开始调用的时间，用来拉开间隔 */
    private long lastStart;

    private final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(MAX_PARALLEL, true);

    private final IntegrationSettings settings;
    private final List<String> command;
    private final ObjectMapper objectMapper;
    private final CallRecorder recorder;

    /**
     * Key 和开关每次调用时从后台配置取（没在后台填就用 FLYAI_API_KEY）；要执行的命令只能在 .env 的 FLYAI_COMMAND 里改，
     * 不开放给后台，免得后台账号能在服务器上执行任意命令。
     */
    public FliggyCli(IntegrationSettings settings,
                     @Value("${life.hotel.fliggy.command:flyai}") String command, ObjectMapper objectMapper,
                     CallRecorder recorder) {
        this.settings = settings;
        this.command = Arrays.asList(command.trim().split("\\s+"));
        this.objectMapper = objectMapper;
        this.recorder = recorder;
    }

    /** 配了 Key 且后台没停用。 */
    public boolean available() {
        return StringUtils.hasText(settings.value("fliggy", "api-key"));
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
            pace();
            return recorder.platform("飞猪", String.join(" ", args), () -> doRun(args));
        } finally {
            gate.release();
        }
    }

    /** 和上一次开始调用至少隔 MIN_GAP_MILLIS，同一时刻挤进来的排队错开。 */
    private void pace() {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long start = Math.max(now, lastStart + MIN_GAP_MILLIS);
            lastStart = start;
            wait = start - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        String apiKey = settings.value("fliggy", "api-key");
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("飞猪没有配置或已在后台停用");
        }
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
