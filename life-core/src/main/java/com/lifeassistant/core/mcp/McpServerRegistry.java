package com.lifeassistant.core.mcp;

import com.lifeassistant.core.config.LifeProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 life.mcp 配置连接远程 MCP 服务，把它们的工具交给技能用。第一次用到才连，连上后缓存；
 * 没配 Key 的服务直接跳过，连接失败只记日志、返回空工具，5 分钟后再试，不影响应用启动和对话。
 */
@Slf4j
@Component
public class McpServerRegistry {

    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, LifeProperties.McpServer> servers;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();

    public McpServerRegistry(LifeProperties properties) {
        this.servers = properties.getMcp();
    }

    /** 一个已连上的服务：客户端和它的工具。 */
    private record Connection(McpSyncClient client, List<ToolCallback> tools) {
    }

    /** 这个服务是否配置了地址和 Key（Key 写成 false 表示关掉）。 */
    public boolean configured(String name) {
        LifeProperties.McpServer server = servers.get(name);
        return server != null && StringUtils.hasText(server.getUrl()) && StringUtils.hasText(server.getApiKey())
                && !"false".equalsIgnoreCase(server.getApiKey());
    }

    /** 取某个服务的工具；没配置、连不上或在重试冷却期内返回空列表。 */
    public List<ToolCallback> tools(String name) {
        if (!configured(name)) {
            return List.of();
        }
        Connection connection = connections.get(name);
        if (connection != null) {
            return connection.tools();
        }
        Instant failed = failedAt.get(name);
        if (failed != null && failed.plus(RETRY_AFTER).isAfter(Instant.now())) {
            return List.of();
        }
        synchronized (this) {
            connection = connections.get(name);
            if (connection == null) {
                connection = connect(name, servers.get(name));
                if (connection == null) {
                    return List.of();
                }
                connections.put(name, connection);
            }
        }
        return connection.tools();
    }

    /** 按名字取某个服务的一个工具，供代码直接调用（不经过模型）；取不到返回空。 */
    public Optional<ToolCallback> tool(String server, String toolName) {
        return tools(server).stream().filter(t -> toolName.equals(t.getToolDefinition().name())).findFirst();
    }

    /** 建 Streamable HTTP 连接并初始化，列出工具；失败返回 null 并记下失败时间。 */
    private Connection connect(String name, LifeProperties.McpServer server) {
        McpSyncClient client = null;
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(server.getUrl())
                    .endpoint(server.getEndpoint())
                    .customizeRequest(b -> b.header(server.getAuthHeader(), server.getAuthPrefix() + server.getApiKey()))
                    .build();
            client = McpClient.sync(transport)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .clientInfo(new McpSchema.Implementation("life-assistant", "0.1.0"))
                    .build();
            client.initialize();
            List<ToolCallback> tools = java.util.Arrays.stream(new SyncMcpToolCallbackProvider(client).getToolCallbacks())
                    .map(t -> (ToolCallback) new ResettingTool(name, t)).toList();
            failedAt.remove(name);
            log.info("已连接 MCP 服务 {}，工具：{}", name,
                    tools.stream().map(t -> t.getToolDefinition().name()).toList());
            return new Connection(client, tools);
        } catch (RuntimeException e) {
            log.warn("连接 MCP 服务 {} 失败，{} 分钟后再试：{}", name, RETRY_AFTER.toMinutes(), e.getMessage());
            failedAt.put(name, Instant.now());
            if (client != null) {
                client.closeGracefully();
            }
            return null;
        }
    }

    /** 调用出错（会话失效、服务端 502 等）时丢掉这个连接，下一轮对话重新连，不用重启应用。 */
    private void reset(String name) {
        Connection old = connections.remove(name);
        if (old != null) {
            log.warn("MCP 服务 {} 调用出错，下次用到时重新连接", name);
            try {
                old.client().closeGracefully();
            } catch (RuntimeException ignored) {
                // 已经断了，不用管
            }
        }
    }

    /** 包一层工具：调用抛异常时重置连接再把异常抛给调用方（模型会看到工具出错，改用别的办法）。 */
    private final class ResettingTool implements ToolCallback {
        private final String server;
        private final ToolCallback delegate;

        ResettingTool(String server, ToolCallback delegate) {
            this.server = server;
            this.delegate = delegate;
        }

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
            try {
                return delegate.call(toolInput);
            } catch (RuntimeException e) {
                reset(server);
                throw e;
            }
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            try {
                return delegate.call(toolInput, toolContext);
            } catch (RuntimeException e) {
                reset(server);
                throw e;
            }
        }
    }

    /** 应用关闭时断开所有连接。 */
    @PreDestroy
    public void close() {
        connections.values().forEach(c -> c.client().closeGracefully());
    }
}
