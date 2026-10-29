package com.lifeassistant.core.mcp;

import com.lifeassistant.core.config.LifeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 没配置或连不上的 MCP 服务返回空工具，不抛异常，不影响对话。 */
class McpServerRegistryTest {

    @Test
    void unconfiguredServerHasNoTools() {
        LifeProperties properties = new LifeProperties();
        LifeProperties.McpServer server = new LifeProperties.McpServer();
        server.setUrl("https://mcp.example.com");
        properties.getMcp().put("rollinggo", server);
        McpServerRegistry registry = new McpServerRegistry(properties);

        assertThat(registry.configured("rollinggo")).isFalse();
        assertThat(registry.tools("rollinggo")).isEmpty();
        assertThat(registry.tools("unknown")).isEmpty();
    }

    @Test
    void unreachableServerReturnsEmptyAndDoesNotRetryImmediately() {
        LifeProperties properties = new LifeProperties();
        LifeProperties.McpServer server = new LifeProperties.McpServer();
        server.setUrl("http://127.0.0.1:1");
        server.setApiKey("key");
        properties.getMcp().put("rollinggo", server);
        McpServerRegistry registry = new McpServerRegistry(properties);

        assertThat(registry.tools("rollinggo")).isEmpty();
        long start = System.nanoTime();
        assertThat(registry.tools("rollinggo")).isEmpty();
        // 冷却期内直接返回，不再去连
        assertThat(System.nanoTime() - start).isLessThan(100_000_000L);
    }
}
