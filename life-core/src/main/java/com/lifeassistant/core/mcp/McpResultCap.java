package com.lifeassistant.core.mcp;

import org.springframework.ai.tool.ToolCallback;

/**
 * MCP 工具（高德、RollingGo 等外部服务）返回给模型的内容限长：控制 Token，也减少外部内容里夹带的东西。
 * 旅行和美食技能都用它包一层。
 */
public final class McpResultCap {

    private McpResultCap() {
    }

    /** MCP 返回给模型的内容最多多少字，太长的截断（控制 Token，也减少外部内容夹带的东西） */
    public static final int MAX = 4000;

    /** 包一层：工具定义和执行不变，返回内容超过 MAX 时截断并注明。 */
    public static ToolCallback capped(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return cut(delegate.call(toolInput));
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return cut(delegate.call(toolInput, toolContext));
            }
        };
    }

    /** 超长的截断，后面注明被截断了。 */
    public static String cut(String text) {
        return text == null || text.length() <= MAX ? text : text.substring(0, MAX) + "…（内容太长，后面截掉了）";
    }

}
