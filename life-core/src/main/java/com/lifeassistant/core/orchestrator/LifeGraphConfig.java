package com.lifeassistant.core.orchestrator;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.skill.SkillRegistry;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 顶层编排图：route（小模型判断技能）→ skill（流式执行技能）→ END。
 * <p>
 * 每个会话是一个 thread，断点存 PostgreSQL，服务重启后状态还在。
 * 状态里只放可序列化的简单值（字符串、数字），身份用 userId/spaceId 而不是对象。
 */
@Configuration
public class LifeGraphConfig {

    public static final String INPUT = "input";
    public static final String USER_ID = "userId";
    public static final String SPACE_ID = "spaceId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String SKILL_ID = "skillId";
    public static final String ANSWER = "answer";
    public static final String NODE_ROUTE = "route";
    public static final String NODE_SKILL = "skill";

    /** 图的断点存储：life.graph.checkpoint=postgres 时用 PostgresSaver，否则用内存（重启即丢）。 */
    @Bean
    public BaseCheckpointSaver graphCheckpointSaver(LifeProperties properties, DataSourceProperties dataSource) {
        if (!"postgres".equalsIgnoreCase(properties.getGraph().getCheckpoint())) {
            return new MemorySaver();
        }
        // PostgresSaver 自己管理连接和表（graph_thread / graph_checkpoint），这里从数据源配置里解析连接信息
        URI uri = URI.create(dataSource.getUrl().substring("jdbc:".length()));
        return PostgresSaver.builder()
                .host(uri.getHost())
                .port(uri.getPort() > 0 ? uri.getPort() : 5432)
                .database(uri.getPath().substring(1))
                .user(dataSource.getUsername())
                .password(dataSource.getPassword())
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

    /** 组装并编译 START → route → skill → END 的编排图，所有状态键都用覆盖（REPLACE）策略。 */
    @Bean
    public CompiledGraph lifeGraph(RouterService routerService, SkillRunner skillRunner, SkillRegistry skillRegistry,
                                   BaseCheckpointSaver graphCheckpointSaver) throws GraphStateException {
        StateGraph graph = new StateGraph("life", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : Set.of(INPUT, USER_ID, SPACE_ID, CONVERSATION_ID, SKILL_ID, ANSWER, "messages")) {
                strategies.put(key, KeyStrategy.REPLACE);
            }
            return strategies;
        });

        graph.addNode(NODE_ROUTE, node_async(state -> {
            RouterService.RouteDecision decision = routerService.route(
                    state.value(INPUT, ""),
                    state.value(SKILL_ID, String.class).orElse(null),
                    userCtx(state.data()).userId(),
                    state.value(CONVERSATION_ID, ""));
            return Map.of(SKILL_ID, decision.skillId());
        }));

        // 返回值里放 Flux<ChatResponse>，Graph 会边生成边往外推 StreamingOutput，结束后把完整回答写回状态
        graph.addNode(NODE_SKILL, node_async(state -> Map.of(ANSWER, skillRunner.stream(
                skillRegistry.get(state.value(SKILL_ID, "")),
                userCtx(state.data()),
                state.value(CONVERSATION_ID, ""),
                state.value(INPUT, "")))));

        graph.addEdge(StateGraph.START, NODE_ROUTE);
        graph.addEdge(NODE_ROUTE, NODE_SKILL);
        graph.addEdge(NODE_SKILL, StateGraph.END);

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(graphCheckpointSaver).build())
                .build());
    }

    /** 从图状态还原用户上下文；状态里没有租户和角色，统一填默认值。 */
    static UserCtx userCtx(Map<String, Object> data) {
        long userId = ((Number) data.get(USER_ID)).longValue();
        Object spaceId = data.get(SPACE_ID);
        return new UserCtx(userId, spaceId == null ? 0L : ((Number) spaceId).longValue(), 0L, Set.of());
    }
}
