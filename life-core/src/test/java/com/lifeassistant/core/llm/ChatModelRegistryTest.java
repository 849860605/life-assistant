package com.lifeassistant.core.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.config.LifeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模型注册：型号解析、按类型建客户端、缺配置时的报错，以及 OpenAI 兼容接口的实际请求。 */
class ChatModelRegistryTest {

    private final LifeProperties properties = new LifeProperties();
    private ChatModelRegistry registry;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        properties.getLlm().getProviders().put("dashscope", provider("dashscope", null, null, "ds-key"));
        properties.getLlm().getProviders().put("deepseek", provider("openai", "https://api.deepseek.com", null, ""));
        registry = new ChatModelRegistry(properties,
                new StaticListableBeanFactory().getBeanProvider(ToolCallingManager.class));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parseSplitsOnlyOnKnownProvider() {
        Set<String> providers = Set.of("dashscope", "deepseek");
        assertThat(ModelSpec.parse("deepseek:deepseek-chat", "dashscope", providers))
                .isEqualTo(new ModelSpec("deepseek", "deepseek-chat"));
        assertThat(ModelSpec.parse("qwen-plus", "dashscope", providers))
                .isEqualTo(new ModelSpec("dashscope", "qwen-plus"));
        // 冒号前不是已配置的提供方：整串当型号
        assertThat(ModelSpec.parse("qwen2.5:7b", "dashscope", providers))
                .isEqualTo(new ModelSpec("dashscope", "qwen2.5:7b"));
    }

    @Test
    void createsModelByProviderType() {
        assertThat(registry.createModel("dashscope")).isInstanceOf(DashScopeChatModel.class);

        properties.getLlm().getProviders().get("deepseek").setApiKey("sk-test");
        assertThat(registry.createModel("deepseek")).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void missingKeyOrProviderFailsWithClearMessage() {
        assertThatThrownBy(() -> registry.client(registry.resolve("deepseek:deepseek-chat")))
                .isInstanceOf(BizException.class).hasMessageContaining("deepseek").hasMessageContaining("API Key");
        assertThatThrownBy(() -> registry.createModel("kimi"))
                .isInstanceOf(BizException.class).hasMessageContaining("没有配置模型提供方 kimi");
    }

    @Test
    void optionsKeepToolSupport() {
        // 必须是 ToolCallingChatOptions，ChatClient 才会把工具传给模型
        assertThat(ChatModelRegistry.options(new ModelSpec("deepseek", "deepseek-chat"), 0.0))
                .isInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void openAiCompatibleProviderUsesBaseUrlPathAndModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 只在 base-url + completions-path 上响应，路径拼错测试就会失败
        server.createContext("/api/paas/v4/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"id":"1","object":"chat.completion","created":1,"model":"glm-4-flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties.getLlm().getProviders().put("zhipu", provider("openai",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/paas", "/v4/chat/completions", "zp-key"));

        ModelSpec spec = registry.resolve("zhipu:glm-4-flash");
        String answer = registry.client(spec).prompt().user("hi")
                .options(ChatModelRegistry.options(spec, 0.0)).call().content();

        assertThat(answer).isEqualTo("你好");
        assertThat(requestBody.get()).contains("\"model\":\"glm-4-flash\"");
    }

    private static LifeProperties.Provider provider(String type, String baseUrl, String path, String apiKey) {
        LifeProperties.Provider provider = new LifeProperties.Provider();
        provider.setType(type);
        provider.setBaseUrl(baseUrl);
        provider.setCompletionsPath(path);
        provider.setApiKey(apiKey);
        return provider;
    }
}
