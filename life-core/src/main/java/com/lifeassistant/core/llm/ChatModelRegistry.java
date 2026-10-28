package com.lifeassistant.core.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 life.llm 配置给每个模型提供方建一个 ChatClient，业务代码只认"提供方:型号"，不依赖具体厂商的类。
 * 客户端在第一次用到时才创建，没用到的提供方不填 Key 也能正常启动。
 */
@Component
public class ChatModelRegistry {

    private final LifeProperties properties;
    private final ToolCallingManager toolCallingManager;
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    public ChatModelRegistry(LifeProperties properties, ObjectProvider<ToolCallingManager> toolCallingManager) {
        this.properties = properties;
        this.toolCallingManager = toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build());
    }

    /** 把用途配置（如 deepseek:deepseek-chat 或 qwen-plus）解析成提供方和型号。 */
    public ModelSpec resolve(String spec) {
        LifeProperties.Llm llm = properties.getLlm();
        return ModelSpec.parse(spec, llm.getDefaultProvider(), llm.getProviders().keySet());
    }

    /** 取提供方对应的 ChatClient，第一次用时创建并缓存；提供方没配置或没填 Key 抛 BizException。 */
    public ChatClient client(ModelSpec spec) {
        return clients.computeIfAbsent(spec.provider(), name -> ChatClient.builder(createModel(name)).build());
    }

    /**
     * 通用调用参数：只设型号和温度（null 表示用模型默认值）。用 ToolCallingChatOptions 而不是普通 ChatOptions，
     * 否则 ChatClient 挂的工具不会传给模型；各家 ChatModel 会把它转换成自己的参数类型。
     */
    public static ChatOptions options(ModelSpec spec, Double temperature) {
        return ToolCallingChatOptions.builder().model(spec.model()).temperature(temperature).build();
    }

    /** 按提供方类型创建 ChatModel：dashscope 走百炼原生接口，openai 走 OpenAI 兼容接口。 */
    ChatModel createModel(String name) {
        LifeProperties.Provider provider = properties.getLlm().getProviders().get(name);
        if (provider == null) {
            throw new BizException(ErrorCode.INTERNAL, "没有配置模型提供方 " + name + "，检查 life.llm.providers");
        }
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "模型提供方 " + name + " 没有填 API Key，检查 .env");
        }
        return switch (provider.getType()) {
            case "dashscope" -> {
                DashScopeApi.Builder api = DashScopeApi.builder().apiKey(provider.getApiKey());
                if (hasText(provider.getBaseUrl())) {
                    api.baseUrl(provider.getBaseUrl());
                }
                yield DashScopeChatModel.builder().dashScopeApi(api.build())
                        .toolCallingManager(toolCallingManager).build();
            }
            case "openai" -> {
                if (!hasText(provider.getBaseUrl())) {
                    throw new BizException(ErrorCode.INTERNAL, "模型提供方 " + name + " 没有填 base-url");
                }
                OpenAiApi.Builder api = OpenAiApi.builder().baseUrl(provider.getBaseUrl()).apiKey(provider.getApiKey());
                if (hasText(provider.getCompletionsPath())) {
                    api.completionsPath(provider.getCompletionsPath());
                }
                yield OpenAiChatModel.builder().openAiApi(api.build()).toolCallingManager(toolCallingManager).build();
            }
            default -> throw new BizException(ErrorCode.INTERNAL,
                    "模型提供方 " + name + " 的 type 只能是 dashscope 或 openai，现在是 " + provider.getType());
        };
    }

    /** 非 null 且不全是空白。 */
    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
