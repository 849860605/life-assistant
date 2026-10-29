package com.lifeassistant.core.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

/**
 * 并行执行工具调用。Spring AI 默认的 DefaultToolCallingManager 把模型一次返回的多个工具调用按顺序逐个执行；
 * 这里改成每个调用一个虚拟线程同时执行，结果按原顺序拼回去，其余行为（ToolContext、异常处理、returnDirect）保持一致。
 * 工具都是无状态的，身份通过 ToolContext 传递，并行执行是安全的。
 */
public class ParallelToolCallingManager implements ToolCallingManager {

    private final ToolCallbackResolver toolCallbackResolver;
    private final ToolExecutionExceptionProcessor exceptionProcessor;
    /** 工具定义的解析逻辑不变，直接交给默认实现 */
    private final DefaultToolCallingManager definitions;

    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver) {
        this.toolCallbackResolver = toolCallbackResolver;
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

    /** 执行一个工具；参数为空时按 {} 处理，工具异常转成给模型看的错误文字。 */
    private String invoke(ToolCallback callback, AssistantMessage.ToolCall call, ToolContext toolContext) {
        String arguments = StringUtils.hasText(call.arguments()) ? call.arguments() : "{}";
        try {
            return callback.call(arguments, toolContext);
        } catch (ToolExecutionException e) {
            return exceptionProcessor.process(e);
        }
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
