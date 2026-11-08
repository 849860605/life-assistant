package com.lifeassistant.core.llm;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.auth.ToolContexts;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 并行执行工具调用。Spring AI 默认的 DefaultToolCallingManager 把模型一次返回的多个工具调用按顺序逐个执行；
 * 这里改成每个调用一个虚拟线程同时执行，结果按原顺序拼回去，其余行为（ToolContext、异常处理、returnDirect）保持一致。
 * 工具都是无状态的，身份通过 ToolContext 传递，并行执行是安全的。每次调用的参数、耗时和成败写入调用日志。
 */
public class ParallelToolCallingManager implements ToolCallingManager {

    private final ToolCallbackResolver toolCallbackResolver;
    private final ToolExecutionExceptionProcessor exceptionProcessor;
    /** 这一轮工具调用太多时拦下 */
    private final java.util.function.BiPredicate<String, String> limiter;
    /** 工具定义的解析逻辑不变，直接交给默认实现 */
    private final DefaultToolCallingManager definitions;
    private final CallRecorder recorder;
    /** 按会话 id 取走用户在回答过程中补充的话 */
    private final Function<String, List<String>> interjections;

    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver) {
        this(toolCallbackResolver, CallRecorder.NOOP, conversationId -> List.of());
    }

    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver, CallRecorder recorder,
                                      Function<String, List<String>> interjections) {
        this(toolCallbackResolver, recorder, interjections, (conversationId, tool) -> false);
    }

    /** limiter(会话 id, 工具名) 返回 true 时这次调用不执行，直接告诉模型停下（防止在几个工具之间来回调停不下来）。 */
    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver, CallRecorder recorder,
                                      Function<String, List<String>> interjections,
                                      java.util.function.BiPredicate<String, String> limiter) {
        this.limiter = limiter;
        this.toolCallbackResolver = toolCallbackResolver;
        this.recorder = recorder;
        this.interjections = interjections;
        this.exceptionProcessor = DefaultToolExecutionExceptionProcessor.builder().build();
        this.definitions = DefaultToolCallingManager.builder().toolCallbackResolver(toolCallbackResolver).build();
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return definitions.resolveToolDefinitions(chatOptions);
    }

    /** 执行模型这一轮要求的全部工具调用，只有一个时直接在当前线程执行。 */
    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage assistantMessage = chatResponse.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> !CollectionUtils.isEmpty(output.getToolCalls()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));
        ToolContext toolContext = buildToolContext(prompt, assistantMessage);
        List<ToolCallback> callbacks = prompt.getOptions() instanceof ToolCallingChatOptions options
                ? options.getToolCallbacks() : List.of();

        List<AssistantMessage.ToolCall> calls = assistantMessage.getToolCalls();
        List<ToolCallback> resolved = calls.stream().map(call -> resolve(call.name(), callbacks)).toList();
        List<String> results = new ArrayList<>(calls.size());
        if (calls.size() == 1) {
            results.add(invoke(resolved.get(0), calls.get(0), toolContext));
        } else {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    ToolCallback callback = resolved.get(i);
                    AssistantMessage.ToolCall call = calls.get(i);
                    futures.add(executor.submit(() -> invoke(callback, call, toolContext)));
                }
                for (Future<String> future : futures) {
                    results.add(future.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("工具调用被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("工具调用失败", e);
            }
        }

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean returnDirect = true;
        for (int i = 0; i < calls.size(); i++) {
            responses.add(new ToolResponseMessage.ToolResponse(calls.get(i).id(), calls.get(i).name(),
                    results.get(i) == null ? "" : results.get(i)));
            returnDirect = returnDirect && resolved.get(i).getToolMetadata().returnDirect();
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(ToolResponseMessage.builder().responses(responses).build());
        // 用户在等的时候补充了话，跟在工具结果后面交给模型，它下一步就能按补充调整，不用等这一轮结束
        CallRecorder.Scope scope = scope(toolContext);
        List<String> added = scope == null ? List.of() : interjections.apply(scope.conversationId());
        if (!added.isEmpty()) {
            history.add(new UserMessage("（你处理的过程中，用户补充了）" + String.join("\n", added)
                    + "\n请把补充考虑进去：小改动（加个地方、换个档次）就接着做，已经查到的结果还能用的继续用，只补查新的；"
                    + "改了目的地、日期这类大前提，就别再按原来的做，按新需求重新安排。回答里用一句话说明按补充改了什么。"));
            returnDirect = false;
        }
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(returnDirect).build();
    }

    /** 先在本次请求挂的工具里找，再交给全局解析器；都找不到说明模型改了工具名。 */
    private ToolCallback resolve(String name, List<ToolCallback> callbacks) {
        ToolCallback callback = callbacks.stream()
                .filter(tool -> name.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElseGet(() -> toolCallbackResolver.resolve(name));
        if (callback == null) {
            throw new IllegalStateException("No ToolCallback found for tool name: " + name);
        }
        return callback;
    }

    /**
     * 执行一个工具；参数为空时按 {} 处理，工具异常转成给模型看的错误文字。
     * 执行期间设置调用归属，工具内部的平台调用记录会带上用户和会话；结束后记一条工具调用日志。
     */
    private String invoke(ToolCallback callback, AssistantMessage.ToolCall call, ToolContext toolContext) {
        String arguments = repairJson(StringUtils.hasText(call.arguments()) ? call.arguments() : "{}");
        CallRecorder.Scope scope = scope(toolContext);
        if (limiter.test(scope.conversationId(), call.name())) {
            return "{\"error\":\"这一轮工具已经调了太多次，不要再调用任何工具：用已经拿到的结果直接回答，查不到的如实告诉用户\"}";
        }
        long start = System.nanoTime();
        String result = null;
        String error = null;
        try {
            result = recorder.withScope(scope, () -> callback.call(arguments, toolContext));
            return result;
        } catch (ToolExecutionException e) {
            error = e.getMessage();
            result = exceptionProcessor.process(e);
            return result;
        } catch (RuntimeException e) {
            error = e.getMessage();
            if (badArguments(e)) {
                // 模型给的参数不对（不是合法 JSON、必填的没给、类型不对）：告诉它改好再调，不让整轮对话失败
                result = "{\"error\":\"参数不对（" + jsonSafe(e.getMessage()) + "），按工具说明补齐必填参数、类型写对后重新调用；"
                        + "没有值的可选参数直接省略，不要留空\"}";
                return result;
            }
            throw e;
        } finally {
            boolean success = error == null && (result == null || !result.startsWith("{\"error\""));
            recorder.tool(scope, call.name(), arguments, result, (System.nanoTime() - start) / 1_000_000, success,
                    error);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 修掉模型常见的坏 JSON：键后面没写值（"a": , 或 "a": }），补成 null。本来就是合法 JSON 的原样返回。 */
    static String repairJson(String json) {
        try {
            JSON.readTree(json);
            return json;
        } catch (Exception e) {
            return json.replaceAll(":\\s*(?=[,}\\]])", ": null");
        }
    }

    /**
     * 异常是不是模型给的参数有问题（Spring AI 把它包成运行时异常）：JSON 解析失败；
     * 必填的基本类型参数（int、boolean 等）没给，反射调用时拆箱 null 报空指针；参数类型对不上。
     */
    static boolean badArguments(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException || m.startsWith("Conversion from JSON")
                    || t instanceof NullPointerException && m.contains("primitiveConversion")
                    || t instanceof IllegalArgumentException && m.contains("argument type mismatch")) {
                return true;
            }
        }
        return false;
    }

    private static String jsonSafe(String s) {
        String text = s == null ? "" : s.length() > 120 ? s.substring(0, 120) : s;
        return text.replace("\\", " ").replace("\"", "'").replace("\n", " ");
    }

    /** 从 ToolContext 取调用归属；取不到的字段为空。 */
    private static CallRecorder.Scope scope(ToolContext toolContext) {
        Map<String, Object> context = toolContext.getContext();
        Long userId = context.get(UserCtx.KEY) instanceof UserCtx ctx ? ctx.userId() : null;
        return new CallRecorder.Scope(userId, (String) context.get(ToolContexts.CONVERSATION_ID),
                (String) context.get(ToolContexts.SKILL_ID));
    }

    /** 和默认实现一样：在请求的 toolContext 基础上加入本轮之前的对话历史。 */
    private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || CollectionUtils.isEmpty(options.getToolContext())) {
            return new ToolContext(Map.of());
        }
        Map<String, Object> context = new HashMap<>(options.getToolContext());
        List<Message> history = new ArrayList<>(prompt.copy().getInstructions());
        history.add(AssistantMessage.builder()
                .content(assistantMessage.getText())
                .properties(assistantMessage.getMetadata())
                .toolCalls(assistantMessage.getToolCalls())
                .build());
        context.put(ToolContext.TOOL_CALL_HISTORY, history);
        return new ToolContext(context);
    }
}
