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
    /** 用户点的是快捷回复：沿用上一轮的技能 */
    public static final String FOLLOW_UP = "followUp";
    public static final String USER_ID = "userId";
    public static final String SPACE_ID = "spaceId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String SKILL_ID = "skillId";
    /** 这一轮用的场景（逗号分隔，空表示全部），分了场景的技能按它带提示词和工具 */
    public static final String SCENES = "scenes";
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
            for (String key : Set.of(INPUT, USER_ID, SPACE_ID, CONVERSATION_ID, SKILL_ID, SCENES, ANSWER, FOLLOW_UP, "messages")) {
                strategies.put(key, KeyStrategy.REPLACE);
            }
            return strategies;
        });

        graph.addNode(NODE_ROUTE, node_async(state -> {
            String last = state.value(SKILL_ID, String.class).orElse(null);
            if (state.value(FOLLOW_UP, Boolean.class).orElse(false) && last != null && skillRegistry.contains(last)) {
                return Map.of(SKILL_ID, last);
            }
            RouterService.RouteDecision decision = routerService.route(
                    state.value(INPUT, ""),
                    state.value(SKILL_ID, String.class).orElse(null),
                    userCtx(state.data()).userId(),
                    state.value(CONVERSATION_ID, ""));
            // scenes 为 null（确认上一轮、路由失败）时沿用上一轮的场景；换了技能就从全部开始
            if (decision.scenes() == null) {
                return decision.skillId().equals(last) ? Map.of(SKILL_ID, decision.skillId())
                        : Map.of(SKILL_ID, decision.skillId(), SCENES, "");
            }
            return Map.of(SKILL_ID, decision.skillId(), SCENES, String.join(",", decision.scenes()));
        }));

        // 返回值里放 Flux<ChatResponse>，Graph 会边生成边往外推 StreamingOutput，结束后把完整回答写回状态
        graph.addNode(NODE_SKILL, node_async(state -> Map.of(ANSWER, skillRunner.stream(
                skillRegistry.get(state.value(SKILL_ID, "")),
                userCtx(state.data()),
                state.value(CONVERSATION_ID, ""),
                state.value(INPUT, ""),
                scenes(state.value(SCENES, ""))))));

        graph.addEdge(StateGraph.START, NODE_ROUTE);
        graph.addEdge(NODE_ROUTE, NODE_SKILL);
        graph.addEdge(NODE_SKILL, StateGraph.END);

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(graphCheckpointSaver).build())
                .build());
    }

    /** 逗号分隔的场景转成集合，空串是空集合（全部带上）。 */
    static Set<String> scenes(String value) {
        return value == null || value.isBlank() ? Set.of()
                : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 从图状态还原用户上下文；状态里没有租户和角色，统一填默认值。 */
    static UserCtx userCtx(Map<String, Object> data) {
        long userId = ((Number) data.get(USER_ID)).longValue();
        Object spaceId = data.get(SPACE_ID);
        return new UserCtx(userId, spaceId == null ? 0L : ((Number) spaceId).longValue(), 0L, Set.of());
    }
}
