package com.lifeassistant.core.skill;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能：新增一个领域只需要实现它并注册成 Bean。
 * <p>
 * S0 的技能都是"提示词 + 工具"的形式，由平台统一用模型流式执行。
 * 以后有多步流程（比如训练计划需要追问和确认）的技能，可以再加一个返回子图的方法。
 */
public interface LifeSkill {

    /** 技能 id，例如 travel */
    String id();

    /** 中文名，给前端和日志看 */
    String name();

    /** 给路由 Agent 看的说明：这个技能负责什么 */
    String description();

    /** 示例问法，提高路由准确率，也用作评测集的种子 */
    List<String> routingExamples();

    /** 技能专属的系统提示词（全部场景的完整版） */
    String systemPrompt();

    /**
     * 技能分场景时：场景 id → 一句话说明，给路由选场景用。不分场景返回空。
     * 分了场景的技能每次只带选中场景的提示词和工具，省 Token、模型也不容易用错工具。
     */
    default Map<String, String> scenes() {
        return Map.of();
    }

    /** 按选中的场景拼系统提示词；scenes 为空（没选、拿不准）时用完整版。 */
    default String systemPrompt(Set<String> scenes) {
        return systemPrompt();
    }

    /** 选中的场景能用的工具名（@Tool 方法名）；返回 null 表示全部。记忆工具总是带着。 */
    default Set<String> toolNames(Set<String> scenes) {
        return null;
    }

    /** 选中的场景要不要带其他来源的工具（高德、RollingGo 的 MCP 工具很多，查询时才带）。 */
    default List<ToolCallback> toolCallbacks(Set<String> scenes) {
        return toolCallbacks();
    }

    /** 带 @Tool 方法的对象 */
    List<Object> tools();

    /** 其他来源的工具，例如高德 MCP */
    default List<ToolCallback> toolCallbacks() {
        return List.of();
    }

    /** 必须让用户确认才能执行的动作 */
    default Set<String> sensitiveActions() {
        return Set.of();
    }

    /** 这个技能用哪个模型，返回"提供方:型号"（可不带前缀），默认用 life.models.skill */
    default String model(LifeProperties.Models models) {
        return models.getSkill();
    }
}
