package com.lifeassistant.core.llm;

import com.lifeassistant.core.auth.ToolContexts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型一次要求的多个工具调用要同时执行，结果按原顺序返回，ToolContext 照常传入。 */
class ParallelToolCallingManagerTest {

    @Test
    void runsToolCallsConcurrentlyAndKeepsOrder() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(slowTool("weather", 500), slowTool("route", 500), slowTool("hotel", 500))
                .toolContext(Map.of("userCtx", "u1"))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("周六去泰山")), options);
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("1", "function", "weather", "{}"),
                new AssistantMessage.ToolCall("2", "function", "route", "{}"),
                new AssistantMessage.ToolCall("3", "function", "hotel", ""))).build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        ParallelToolCallingManager manager = new ParallelToolCallingManager(new DelegatingToolCallbackResolver(List.of()));

        long start = System.currentTimeMillis();
        ToolExecutionResult result = manager.executeToolCalls(prompt, response);
        long elapsed = System.currentTimeMillis() - start;

        // 三个各 500 毫秒的工具串行要 1.5 秒，并行应该在 1 秒内
        assertThat(elapsed).isLessThan(1000);
        ToolResponseMessage message = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(message.getResponses()).extracting(ToolResponseMessage.ToolResponse::name)
                .containsExactly("weather", "route", "hotel");
        assertThat(message.getResponses()).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("weather:u1", "route:u1", "hotel:u1");
    }

    /** 回答过程中用户补充的话，跟在工具结果后面作为用户消息交给模型；没有补充时历史最后一条还是工具结果。 */
    @Test
    void appendsInterjectionsAfterToolResults() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(slowTool("hotel", 0))
                .toolContext(Map.of("userCtx", "u1", ToolContexts.CONVERSATION_ID, "c1"))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("巴厘岛 8 天")), options);
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("1", "function", "hotel", "{}"))).build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        java.util.Deque<String> inbox = new java.util.ArrayDeque<>(List.of("还要去科莫多"));
        ParallelToolCallingManager manager = new ParallelToolCallingManager(new DelegatingToolCallbackResolver(List.of()),
                com.lifeassistant.core.audit.CallRecorder.NOOP,
                id -> "c1".equals(id) && !inbox.isEmpty() ? List.of(inbox.poll()) : List.of());

        List<org.springframework.ai.chat.messages.Message> first = manager.executeToolCalls(prompt, response).conversationHistory();
        assertThat(first.getLast()).isInstanceOf(UserMessage.class);
        assertThat(first.getLast().getText()).contains("还要去科莫多");
        assertThat(first.get(first.size() - 2)).isInstanceOf(ToolResponseMessage.class);

        List<org.springframework.ai.chat.messages.Message> second = manager.executeToolCalls(prompt, response).conversationHistory();
        assertThat(second.getLast()).isInstanceOf(ToolResponseMessage.class);
    }

    /** 睡 millis 毫秒后返回"工具名:用户"，用来验证并行和 ToolContext。 */
    private static ToolCallback slowTool(String name, long millis) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return name + ":" + toolContext.getContext().get("userCtx");
            }
        };
    }
}
