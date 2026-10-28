package com.lifeassistant.core.skill;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
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

    /** 技能专属的系统提示词 */
    String systemPrompt();

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
