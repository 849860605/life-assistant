package com.lifeassistant.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 记录调用日志（t_tool_call_log）：模型发起的工具调用记为 TOOL，工具内部调美团、携程、飞猪等平台接口记为 PLATFORM。
 * 后台用它看各平台的失败率和耗时，用户说"查不到"时按会话查原因。写库失败只打日志，不影响对话。
 */
@Slf4j
@Component
public class CallRecorder {

    /** 不写库的实例，给单元测试和没有数据库的场景用 */
    public static final CallRecorder NOOP = new CallRecorder(null, new ObjectMapper());

    private static final int MAX_DIGEST = 300;

    /**
     * 当前在为谁执行工具。工具调用开始时设置；工具内部用虚拟线程并行查平台时，新线程会继承它，
     * 平台调用记录就能关联到用户、会话和技能。
     */
    private static final InheritableThreadLocal<Scope> SCOPE = new InheritableThreadLocal<>();

    private final ToolCallLogMapper mapper;
    private final ObjectMapper objectMapper;

    public CallRecorder(ToolCallLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 调用归属：用户、会话、技能，都可能为空。 */
    public record Scope(Long userId, String conversationId, String skillId) {
    }

    /** 当前线程所在的调用归属，工具执行期间才有。 */
    public static Scope current() {
        return SCOPE.get();
    }

    /** 在 scope 下执行 body，执行完恢复原来的 scope。 */
    public <T> T withScope(Scope scope, Supplier<T> body) {
        Scope previous = SCOPE.get();
        SCOPE.set(scope);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        }
    }

    /** 记一次模型发起的工具调用；arguments 是模型给的 JSON 参数。 */
    public void tool(Scope scope, String toolName, String arguments, String output, long latencyMs, boolean success,
                     String error) {
        write(scope, "TOOL", toolName, arguments, output, latencyMs, success, error);
    }

    /** 执行一次平台接口调用并记录耗时和成败；异常原样抛出。input 是给平台的问题或参数，用来排查。 */
    public <T> T platform(String platform, String input, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            T result = call.get();
            write(SCOPE.get(), "PLATFORM", platform, input, result == null ? null : result.toString(),
                    elapsedMs(start), true, null);
            return result;
        } catch (RuntimeException e) {
            write(SCOPE.get(), "PLATFORM", platform, input, null, elapsedMs(start), false, e.getMessage());
            throw e;
        }
    }

    private void write(Scope scope, String kind, String name, String input, String output, long latencyMs,
                       boolean success, String error) {
        if (mapper == null) {
            return;
        }
        try {
            mapper.insert(new ToolCallLogMapper.Row(scope == null ? null : scope.userId(),
                    scope == null ? null : scope.conversationId(), scope == null ? null : scope.skillId(), kind,
                    truncate(name, 64), toJson(input), truncate(output, MAX_DIGEST), (int) latencyMs, success,
                    truncate(error, 1000)));
        } catch (RuntimeException e) {
            log.warn("记录调用日志失败：{}", e.getMessage());
        }
    }

    /** 参数本身是 JSON 对象就原样存，否则包成 {"text": ...}。 */
    private String toJson(String input) {
        if (input == null) {
            return null;
        }
        try {
            if (objectMapper.readTree(input).isObject()) {
                return input;
            }
        } catch (Exception ignored) {
            // 不是 JSON，按文本包一层
        }
        try {
            return objectMapper.writeValueAsString(Map.of("text", input));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
