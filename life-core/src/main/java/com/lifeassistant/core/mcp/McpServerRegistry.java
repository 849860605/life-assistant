package com.lifeassistant.core.mcp;

import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.integration.IntegrationSettings;
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
 * RollingGo 和高德 MCP 的 Key、地址、开关以后台"第三方平台"为准，后台改了下次用到时自动重连。
 */
@Slf4j
@Component
public class McpServerRegistry {

    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, LifeProperties.McpServer> servers;
    private final IntegrationSettings settings;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    /** 连接失败的时间和当时的配置；配置变了就不用等冷却，马上重连 */
    private final Map<String, Failure> failedAt = new ConcurrentHashMap<>();

    public McpServerRegistry(LifeProperties properties, IntegrationSettings settings) {
        this.servers = properties.getMcp();
        this.settings = settings;
    }

    /** 一个已连上的服务：客户端、它的工具和连接时用的配置。 */
    private record Connection(McpSyncClient client, List<ToolCallback> tools, Target target) {
    }

    /** 实际要连的地址和请求头。 */
    private record Target(String url, String endpoint, String header, String value) {
    }

    private record Failure(Instant at, Target target) {
    }

    /** 这个服务是否配置了地址和 Key，且后台没有停用。 */
    public boolean configured(String name) {
        return target(name) != null;
    }

    /**
     * 现在该怎么连这个服务；没配置或停用返回 null。rollinggo 的 Key 和地址取后台配置，
     * amap 用后台"高德 Web 服务"的 Key 拼地址、开关看"高德 MCP"；其他服务照 life.mcp 原样（Key 写成 false 表示关掉）。
     */
    private Target target(String name) {
        LifeProperties.McpServer server = servers.get(name);
        if (server == null) {
            return null;
        }
        String url = server.getUrl();
        String endpoint = server.getEndpoint();
        String key = server.getApiKey();
        if ("rollinggo".equals(name)) {
            url = settings.value("rollinggo", "url");
            key = settings.value("rollinggo", "api-key");
        } else if ("amap".equals(name)) {
            String amapKey = settings.value("amap", "key");
            if (!settings.enabled("amap-mcp") || !StringUtils.hasText(amapKey)) {
                return null;
            }
            endpoint = "/mcp?key=" + amapKey;
            key = "on";
        }
        if (!StringUtils.hasText(url) || !StringUtils.hasText(key) || "false".equalsIgnoreCase(key)) {
            return null;
        }
        return new Target(url, endpoint, server.getAuthHeader(), server.getAuthPrefix() + key);
    }

    /** 取某个服务的工具；没配置、连不上或在重试冷却期内返回空列表。配置和上次连接时不同就断开重连。 */
    public List<ToolCallback> tools(String name) {
        Target target = target(name);
        if (target == null) {
            reset(name);
            return List.of();
        }
        Connection connection = connections.get(name);
        if (connection != null && connection.target().equals(target)) {
            return connection.tools();
        }
        Failure failed = failedAt.get(name);
        if (failed != null && failed.target().equals(target) && failed.at().plus(RETRY_AFTER).isAfter(Instant.now())) {
            return List.of();
        }
        synchronized (this) {
            connection = connections.get(name);
            if (connection != null && !connection.target().equals(target)) {
                reset(name);
                connection = null;
            }
            if (connection == null) {
                connection = connect(name, target);
                if (connection == null) {
                    return List.of();
                }
                connections.put(name, connection);
            }
        }
        return connection.tools();
    }

    /** 后台"测试"用：断开重连一次，返回工具个数；没配置返回 -1，连不上返回 0。 */
    public int reconnect(String name) {
        reset(name);
        failedAt.remove(name);
        return configured(name) ? tools(name).size() : -1;
    }

    /** 按名字取某个服务的一个工具，供代码直接调用（不经过模型）；取不到返回空。 */
    public Optional<ToolCallback> tool(String server, String toolName) {
        return tools(server).stream().filter(t -> toolName.equals(t.getToolDefinition().name())).findFirst();
    }

    /** 建 Streamable HTTP 连接并初始化，列出工具；失败返回 null 并记下失败时间。 */
    private Connection connect(String name, Target target) {
        McpSyncClient client = null;
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(target.url())
                    .endpoint(target.endpoint())
                    .customizeRequest(b -> b.header(target.header(), target.value()))
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
            return new Connection(client, tools, target);
        } catch (RuntimeException e) {
            log.warn("连接 MCP 服务 {} 失败，{} 分钟后再试：{}", name, RETRY_AFTER.toMinutes(), e.getMessage());
            failedAt.put(name, new Failure(Instant.now(), target));
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
