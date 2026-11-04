package com.lifeassistant.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用自定义配置，前缀 life。密钥类配置一律来自环境变量（.env），不写进仓库。
 */
@Data
@ConfigurationProperties(prefix = "life")
public class LifeProperties {

    private Crypto crypto = new Crypto();
    private Llm llm = new Llm();
    private Models models = new Models();
    private Bark bark = new Bark();
    private QWeather qweather = new QWeather();
    private Amap amap = new Amap();
    private Search search = new Search();
    private Pdf pdf = new Pdf();
    /** 远程 MCP 服务：名字 → 连接配置 */
    private Map<String, McpServer> mcp = new LinkedHashMap<>();
    private Graph graph = new Graph();
    private Eval eval = new Eval();
    private Storage storage = new Storage();

    /** 字段加密配置。 */
    @Data
    public static class Crypto {
        /** AES-256 密钥，Base64 编码的 32 字节。生成：openssl rand -base64 32 */
        private String key;
    }

    /** 模型提供方配置：每个提供方一个连接（地址 + Key），用途到型号的映射在 {@link Models}。 */
    @Data
    public static class Llm {
        /** 型号没写提供方前缀时用哪个提供方 */
        private String defaultProvider = "dashscope";
        /** 提供方名 → 连接配置，名字就是型号前缀，例如 deepseek:deepseek-chat 里的 deepseek */
        private Map<String, Provider> providers = new LinkedHashMap<>();
        /** 型号（"提供方:型号"）→ 单价，用来算每次调用的费用；没配的型号不算费用 */
        private Map<String, Price> pricing = new LinkedHashMap<>();
    }

    /** 模型单价，单位：元 / 百万 Token。 */
    @Data
    public static class Price {
        /** 输入（缓存未命中） */
        private BigDecimal input = BigDecimal.ZERO;
        /** 输入（缓存命中） */
        private BigDecimal cachedInput = BigDecimal.ZERO;
        private BigDecimal output = BigDecimal.ZERO;
    }

    /** 一个模型提供方的连接配置。 */
    @Data
    public static class Provider {
        /** dashscope：阿里云百炼原生接口；openai：OpenAI 兼容接口（DeepSeek、Kimi、智谱、豆包、OpenAI 等） */
        private String type = "openai";
        /** 接口地址，不含 /v1/chat/completions；dashscope 可不填 */
        private String baseUrl;
        /** 对话接口路径，为空用客户端默认值（openai 是 /v1/chat/completions）；智谱、豆包这类路径不同的要填 */
        private String completionsPath;
        private String apiKey;
        /** 放 Key 的请求头，空表示 Authorization: Bearer；有的厂商要放在别的头里（如小红书点点的 api-key） */
        private String authHeader;
        /** 关掉思考要额外带的请求参数（openai 类型才用），打分、分类这类简单任务用，例如 DeepSeek 是 thinking.type=disabled */
        private Map<String, Object> noThinking;
    }

    /** 高德 Web 服务配置；地图能力走高德 MCP，这里的 Key 用于天气兜底。 */
    @Data
    public static class Amap {
        /** Web 服务 Key：后端查地点、路线、静态地图 */
        private String key;
        /** Web 端（JS API）Key：页面上的可拖动地图 */
        private String jsKey;
        /** JS API 的安全密钥，只在后端转发高德请求时加上 */
        private String jsSecurity;
    }

    /** 一个远程 MCP 服务（Streamable HTTP）。apiKey 为空表示没开通，不会去连。 */
    @Data
    public static class McpServer {
        /** 服务地址，例如 https://mcp.rollinggo.cn */
        private String url;
        /** MCP 接口路径，例如 /mcp */
        private String endpoint = "/mcp";
        private String apiKey;
        /** 放 Key 的请求头和前缀，默认 Authorization: Bearer <key> */
        private String authHeader = "Authorization";
        private String authPrefix = "Bearer ";
    }

    /** 行程导出 PDF 的配置。 */
    @Data
    public static class Pdf {
        /** 中文字体目录，放 NotoSansSC-Regular.ttf 和 NotoSansSC-Bold.ttf（tools/fetch-fonts.sh 下载） */
        private String fontDir = "data/fonts";
    }

    /** 联网搜索配置，目前用博查。 */
    @Data
    public static class Search {
        private String bochaApiKey;
        private String bochaBaseUrl = "https://api.bochaai.com";
    }

    /** 按用途选模型，值是"提供方:型号"，例如 deepseek:deepseek-chat；不写前缀用 llm.default-provider。 */
    @Data
    public static class Models {
        private String router = "qwen-turbo";
        private String skill = "qwen-plus";
        private String planner = "qwen-max";
        private String vision = "qwen-vl-plus";
        /** 每次带给模型的最近消息条数 */
        private int memoryWindow = 20;
    }

    /** Bark 推送配置。 */
    @Data
    public static class Bark {
        /** 官方服务或自建 bark-server 的地址 */
        private String server = "https://api.day.app";
    }

    /** 和风天气接口配置。 */
    @Data
    public static class QWeather {
        /** 和风天气控制台里分配给你的 API Host，例如 abcxyz.re.qweatherapi.com */
        private String apiHost;
        private String apiKey;
    }

    /** 编排图配置。 */
    @Data
    public static class Graph {
        /** postgres：断点存数据库；memory：只存内存（测试用） */
        private String checkpoint = "postgres";
    }

    /** 评测配置。 */
    @Data
    public static class Eval {
        /** 评测用例 yaml 所在目录，相对进程工作目录 */
        private String casesDir = "eval/cases";
    }

    /** 文件存储配置。 */
    @Data
    public static class Storage {
        /** 本地存储根目录，相对进程工作目录 */
        private String localDir = "data/files";
    }
}
