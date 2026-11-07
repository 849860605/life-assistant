package com.lifeassistant.core.llm;

import com.lifeassistant.core.config.LifeProperties;

import java.util.Map;

/**
 * 模型配置的来源（后台在数据库里配的提供方、用途、单价）。ChatModelRegistry 优先用它，
 * 它还没有内容时回退到 application.yml 的 life.llm 和 life.models。
 */
public interface LlmCatalog {

    /** 启用的提供方：名字 → 连接配置（Key 已解密）。 */
    Map<String, LifeProperties.Provider> providers();

    /** 用途（ROUTER、SKILL、VISION）→ "提供方:型号"，没分配的不在里面。 */
    Map<String, String> roles();

    /** 型号（"提供方:型号"）的单价，没配返回 null。 */
    LifeProperties.Price price(String modelId);

    /** 型号的思考设置，没配返回 null（按关闭处理）。 */
    Thinking thinking(String modelId);

    /** 思考设置：开不开、深度 LOW / MEDIUM / HIGH / CUSTOM、自定义预算（Token）。 */
    record Thinking(boolean enabled, String level, Integer budget) {
    }
}
