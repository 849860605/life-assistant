package com.lifeassistant.core.orchestrator;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.conversation.Conversation;
import com.lifeassistant.core.conversation.ConversationService;
import com.lifeassistant.core.skill.SkillRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的入口：校验会话归属 → 运行编排图 → 把图的输出转换成前端事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private final CompiledGraph lifeGraph;
    private final ConversationService conversationService;
    private final SkillRegistry skillRegistry;

    /**
     * 以会话 id 作为图的 thread 运行一轮，流式输出事件，正常结束时补一个 done；
     * 出错时只发 error（业务异常透出原文，其他异常给通用提示）。
     *
     * @param ctx 必须在请求线程里取好，图里的节点拿不到登录态
     */
    public Flux<ChatEvent> chat(UserCtx ctx, String conversationId, String input) {
        Conversation conversation = conversationService.getOwned(ctx, conversationId);
        String firstInput = conversation.getTitle() == null ? input : null;

        Map<String, Object> inputs = new HashMap<>();
        inputs.put(LifeGraphConfig.INPUT, input);
        inputs.put(LifeGraphConfig.USER_ID, ctx.userId());
        inputs.put(LifeGraphConfig.SPACE_ID, ctx.spaceId());
        inputs.put(LifeGraphConfig.CONVERSATION_ID, conversationId);
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();

        return lifeGraph.stream(inputs, config)
                // CompiledGraph.stream 内部用 flatMap 把输出转出来，下游按 1 个 1 个要（SSE 背压）时
                // 突发到达的分片会被打乱顺序。这里一次把需求要满，flatMap 就会按到达顺序直接放行，再由缓冲按顺序交给下游
                .onBackpressureBuffer()
                .concatMap(output -> Flux.fromIterable(toEvents(output, conversationId, firstInput)))
                .concatWith(Flux.just(ChatEvent.done()))
                .onErrorResume(e -> {
                    log.error("对话 {} 出错", conversationId, e);
                    String message = e instanceof BizException biz ? biz.getMessage() : "这次回答出错了，请稍后再试";
                    return Flux.just(ChatEvent.error(message));
                });
    }

    /**
     * 图的每个输出都是 StreamingOutput：节点执行完是 GRAPH_NODE_FINISHED，
     * 技能节点里模型逐字生成时是 GRAPH_NODE_STREAMING（chunk 是增量文本）。
     * 只转换两类：增量文本 → delta；route 节点完成 → 更新会话（技能、首句标题）并发 route，其余忽略。
     */
    private List<ChatEvent> toEvents(NodeOutput output, String conversationId, String firstInput) {
        if (!(output instanceof StreamingOutput<?> streaming)) {
            return List.of();
        }
        if (streaming.getOutputType() == OutputType.GRAPH_NODE_STREAMING) {
            return streaming.chunk() == null || streaming.chunk().isEmpty()
                    ? List.of()
                    : List.of(ChatEvent.delta(streaming.chunk()));
        }
        if (LifeGraphConfig.NODE_ROUTE.equals(output.node())) {
            String skillId = output.state().value(LifeGraphConfig.SKILL_ID, "");
            conversationService.touch(conversationId, skillId, firstInput);
            return List.of(ChatEvent.route(skillId, skillRegistry.get(skillId).name()));
        }
        return List.of();
    }
}
