package com.lifeassistant.core.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给每个模型提供方建一个 ChatClient，业务代码只认"提供方:型号"，不依赖具体厂商的类。
 * 提供方、用途、单价优先取后台配置（{@link LlmCatalog}），没有时用 application.yml 的 life.llm 和 life.models。
 * 客户端在第一次用到时才创建；后台改了提供方的地址或 Key，下次用到时按新配置重建。
 */
@Component
public class ChatModelRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 用途：路由、打分、建议这类小任务 */
    public static final String ROUTER = "ROUTER";
    /** 用途：对话（用户没自己选模型时用） */
    public static final String SKILL = "SKILL";
    /** 用途：看图 */
    public static final String VISION = "VISION";

    private final LifeProperties properties;
    private final ToolCallingManager toolCallingManager;
    private final LlmCatalog catalog;
    private final Map<String, Cached> clients = new ConcurrentHashMap<>();

    /** 建好的客户端和建它时用的配置，配置变了就重建。 */
    private record Cached(LifeProperties.Provider provider, ChatClient client) {
    }

    /** Spring 用：有后台配置时用后台的。 */
    @Autowired
    public ChatModelRegistry(LifeProperties properties, ObjectProvider<ToolCallingManager> toolCallingManager,
                             ObjectProvider<LlmCatalog> catalog) {
        this.properties = properties;
        this.toolCallingManager = toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build());
        this.catalog = catalog.getIfAvailable();
    }

    /** 只用 application.yml 配置（测试用）。 */
    public ChatModelRegistry(LifeProperties properties, ObjectProvider<ToolCallingManager> toolCallingManager) {
        this.properties = properties;
        this.toolCallingManager = toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build());
        this.catalog = null;
    }

    /** 当前可用的提供方：后台配了就用后台的，否则用 yml 的。 */
    public Map<String, LifeProperties.Provider> providers() {
        if (catalog != null) {
            Map<String, LifeProperties.Provider> fromDb = catalog.providers();
            if (!fromDb.isEmpty()) {
                return fromDb;
            }
        }
        return properties.getLlm().getProviders();
    }

    /** 一个提供方的连接配置，没有返回 null。 */
    public LifeProperties.Provider provider(String name) {
        return providers().get(name);
    }

    /**
     * 各用途实际用的型号：后台分配过的用后台的，没分配的用 life.models。
     * 返回的是副本，调用方当只读用。
     */
    public LifeProperties.Models models() {
        LifeProperties.Models base = properties.getModels();
        LifeProperties.Models out = new LifeProperties.Models();
        out.setRouter(base.getRouter());
        out.setSkill(base.getSkill());
        out.setPlanner(base.getPlanner());
        out.setVision(base.getVision());
        out.setMemoryWindow(base.getMemoryWindow());
        if (catalog != null) {
            Map<String, String> roles = catalog.roles();
            if (roles.containsKey(ROUTER)) {
                out.setRouter(roles.get(ROUTER));
            }
            if (roles.containsKey(SKILL)) {
                out.setSkill(roles.get(SKILL));
                out.setPlanner(roles.get(SKILL));
            }
            if (roles.containsKey(VISION)) {
                out.setVision(roles.get(VISION));
            }
        }
        return out;
    }

    /** 型号单价（元/百万 Token）：后台配的优先，没有用 yml 的 pricing，都没有返回 null。 */
    public LifeProperties.Price price(String modelId) {
        LifeProperties.Price price = catalog == null ? null : catalog.price(modelId);
        return price != null ? price : properties.getLlm().getPricing().get(modelId);
    }

    /** 把用途配置（如 deepseek:deepseek-chat 或 qwen-plus）解析成提供方和型号。 */
    public ModelSpec resolve(String spec) {
        return ModelSpec.parse(spec, properties.getLlm().getDefaultProvider(), providers().keySet());
    }

    /** 取提供方对应的 ChatClient（不带思考参数），第一次用时创建并缓存，配置变了重建；提供方没配置或没填 Key 抛 BizException。 */
    public ChatClient client(ModelSpec spec) {
        return client(spec, Map.of());
    }

    /** 对话用的 ChatClient：按这个模型在后台的思考设置（开关、深度）带上思考参数。 */
    public ChatClient chatClient(ModelSpec spec) {
        return client(spec, thinkingParams(spec, false));
    }

    /** 简单任务（路由、打分、追问建议、美食意图）用的 ChatClient：不管模型怎么设，都关掉思考。 */
    public ChatClient quickClient(ModelSpec spec) {
        return client(spec, thinkingParams(spec, true));
    }

    /**
     * 按提供方和附加请求参数缓存 ChatClient。附加参数（思考开关）在发请求时写进请求体（见 ExtraBodyClients）：
     * Spring AI 1.1 无论调用参数还是默认参数里的 extraBody 都发不出去。
     */
    private ChatClient client(ModelSpec spec, Map<String, Object> extraBody) {
        LifeProperties.Provider provider = provider(spec.provider());
        boolean extra = provider != null && "openai".equals(provider.getType()) && !extraBody.isEmpty();
        String key = spec.provider() + (extra ? "|" + extraBody : "");
        Cached cached = clients.get(key);
        if (cached != null && provider != null && cached.provider().equals(provider)) {
            return cached.client();
        }
        ChatClient client = ChatClient.builder(createModel(spec.provider(), null, extra ? extraBody : null)).build();
        clients.put(key, new Cached(copy(provider), client));
        return client;
    }

    /**
     * 通用调用参数：只设型号和温度（null 表示用模型默认值）。用 ToolCallingChatOptions 而不是普通 ChatOptions，
     * 否则 ChatClient 挂的工具不会传给模型；各家 ChatModel 会把它转换成自己的参数类型。
     */
    public static ChatOptions options(ModelSpec spec, Double temperature) {
        return ToolCallingChatOptions.builder().model(spec.model()).temperature(temperature).build();
    }

    /**
     * 思考相关的请求参数，按提供方的思考参数格式拼：
     * QWEN 是 enable_thinking（开时带 thinking_budget），TYPE 是 thinking.type（DeepSeek、智谱、豆包，不能调深度），
     * OPENAI 是 reasoning_effort，CUSTOM 只在关闭时带后台填的 JSON，NONE 什么都不带。
     * 格式没配时：配了关闭参数按 CUSTOM，否则按 NONE。forceOff 为 true 时一律按关闭。
     */
    public Map<String, Object> thinkingParams(ModelSpec spec, boolean forceOff) {
        LifeProperties.Provider provider = provider(spec.provider());
        if (provider == null) {
            return Map.of();
        }
        LlmCatalog.Thinking t = catalog == null ? null : catalog.thinking(spec.id());
        boolean on = !forceOff && t != null && t.enabled();
        boolean hasOff = provider.getNoThinking() != null && !provider.getNoThinking().isEmpty();
        String style = provider.getThinkingStyle() != null ? provider.getThinkingStyle() : hasOff ? "CUSTOM" : "NONE";
        String level = t == null || t.level() == null ? "MEDIUM" : t.level();
        return switch (style) {
            case "QWEN" -> on ? Map.of("enable_thinking", true, "thinking_budget", budget(level, t)) : Map.of("enable_thinking", false);
            case "TYPE" -> Map.of("thinking", Map.of("type", on ? "enabled" : "disabled"));
            case "OPENAI" -> Map.of("reasoning_effort", on ? switch (level) {
                case "LOW" -> "low";
                case "HIGH" -> "high";
                default -> "medium";
            } : "minimal");
            case "CUSTOM" -> on || !hasOff ? Map.of() : provider.getNoThinking();
            default -> Map.of();
        };
    }

    /** 思考预算（Token）：低 1024、中 4096、高 16384，自定义用后台填的 */
    private static int budget(String level, LlmCatalog.Thinking t) {
        return switch (level) {
            case "LOW" -> 1024;
            case "HIGH" -> 16384;
            case "CUSTOM" -> t != null && t.budget() != null ? t.budget() : 4096;
            default -> 4096;
        };
    }

    /**
     * 测试连接：用这个型号回一句很短的话，返回回复内容；失败抛原始异常，由调用方整理成提示。
     * 配了关闭思考的带上，否则思考型模型会把 Token 花在思考上、回复是空的。
     */
    public String ping(ModelSpec spec) {
        LifeProperties.Provider provider = provider(spec.provider());
        Map<String, Object> off = thinkingParams(spec, true);
        ChatOptions options = ToolCallingChatOptions.builder().model(spec.model()).maxTokens(200).build();
        // 只试一次：默认的重试要好几分钟才报错，测试时直接看到原因更有用
        ChatClient once = ChatClient.builder(createModel(spec.provider(), RetryTemplate.builder().maxAttempts(1).build(),
                provider != null && "openai".equals(provider.getType()) && !off.isEmpty() ? off : null)).build();
        return once.prompt().user("请只回复两个字：你好").options(options).call().content();
    }

    /**
     * 拉提供方的型号列表（OpenAI 兼容的 GET /models），后台添加模型时下拉选；百炼走它的兼容模式地址。
     * 没填 Key、接口不支持或连不上时抛 BizException，提示改成手填。
     */
    public List<String> remoteModels(String name) {
        LifeProperties.Provider provider = provider(name);
        if (provider == null || !hasText(provider.getApiKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个提供方还没填 API Key，填好后才能拉型号列表");
        }
        String url;
        if ("dashscope".equals(provider.getType())) {
            url = (hasText(provider.getBaseUrl()) ? trimSlash(provider.getBaseUrl()) : "https://dashscope.aliyuncs.com")
                    + "/compatible-mode/v1/models";
        } else {
            String path = hasText(provider.getCompletionsPath())
                    ? provider.getCompletionsPath().replace("chat/completions", "models") : "/v1/models";
            url = trimSlash(provider.getBaseUrl()) + path;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
        if (hasText(provider.getAuthHeader())) {
            request.header(provider.getAuthHeader().trim(), provider.getApiKey());
        } else {
            request.header("Authorization", "Bearer " + provider.getApiKey());
        }
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "提供方没返回型号列表（HTTP " + response.statusCode() + "），可以直接手填型号");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode m : JSON.readTree(response.body()).path("data")) {
                if (hasText(m.path("id").asText(null))) {
                    ids.add(m.path("id").asText());
                }
            }
            Collections.sort(ids);
            return ids;
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BAD_REQUEST, "拉型号列表被中断，可以直接手填型号");
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉型号列表失败，可以直接手填型号");
        }
    }

    /** 去掉地址末尾的斜杠，方便拼路径。 */
    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 按提供方类型创建 ChatModel，用默认的重试策略。 */
    ChatModel createModel(String name) {
        return createModel(name, null, null);
    }

    /** 按提供方类型创建 ChatModel：dashscope 走百炼原生接口，openai 走 OpenAI 兼容接口；retry 为空用默认重试。 */
    /** extraBody 是每次请求都带的附加参数（思考开关），只对 OpenAI 兼容接口有效，可为空。 */
    ChatModel createModel(String name, RetryTemplate retry, Map<String, Object> extraBody) {
        LifeProperties.Provider provider = provider(name);
        if (provider == null) {
            throw new BizException(ErrorCode.INTERNAL, "没有配置模型提供方 " + name + "，到后台的模型配置里加上");
        }
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "模型提供方 " + name + " 没有填 API Key，到后台的模型配置里填上");
        }
        return switch (provider.getType()) {
            case "dashscope" -> {
                DashScopeApi.Builder api = DashScopeApi.builder().apiKey(provider.getApiKey());
                if (hasText(provider.getBaseUrl())) {
                    api.baseUrl(provider.getBaseUrl());
                }
                DashScopeChatModel.Builder model = DashScopeChatModel.builder().dashScopeApi(api.build())
                        .toolCallingManager(toolCallingManager);
                if (retry != null) {
                    model.retryTemplate(retry);
                }
                yield model.build();
            }
            case "openai" -> {
                if (!hasText(provider.getBaseUrl())) {
                    throw new BizException(ErrorCode.INTERNAL, "模型提供方 " + name + " 没有填接口地址");
                }
                OpenAiApi.Builder api = OpenAiApi.builder().baseUrl(provider.getBaseUrl());
                if (hasText(provider.getAuthHeader())) {
                    // Key 放在指定的请求头里，不加 Authorization: Bearer
                    MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
                    headers.add(provider.getAuthHeader().trim(), provider.getApiKey());
                    api.apiKey(new NoopApiKey()).headers(headers);
                } else {
                    api.apiKey(provider.getApiKey());
                }
                if (hasText(provider.getCompletionsPath())) {
                    api.completionsPath(provider.getCompletionsPath());
                }
                if (extraBody != null && !extraBody.isEmpty()) {
                    // 思考开关等附加参数在序列化请求体时写进去（Spring AI 自己的 extraBody 发不出去）
                    api.restClientBuilder(ExtraBodyClients.restClient(extraBody)).webClientBuilder(ExtraBodyClients.webClient(extraBody));
                }
                OpenAiChatModel.Builder model = OpenAiChatModel.builder().openAiApi(api.build())
                        .toolCallingManager(toolCallingManager);
                if (retry != null) {
                    model.retryTemplate(retry);
                }
                yield model.build();
            }
            default -> throw new BizException(ErrorCode.INTERNAL,
                    "模型提供方 " + name + " 的类型只能是 dashscope 或 openai，现在是 " + provider.getType());
        };
    }

    /** 记下建客户端时的配置（yml 的配置对象可能被改，存一份副本才比得出变化）。 */
    private static LifeProperties.Provider copy(LifeProperties.Provider p) {
        LifeProperties.Provider c = new LifeProperties.Provider();
        c.setType(p.getType());
        c.setBaseUrl(p.getBaseUrl());
        c.setCompletionsPath(p.getCompletionsPath());
        c.setApiKey(p.getApiKey());
        c.setAuthHeader(p.getAuthHeader());
        c.setNoThinking(p.getNoThinking());
        return c;
    }

    /** 非 null 且不全是空白。 */
    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
