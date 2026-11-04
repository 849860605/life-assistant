package com.lifeassistant.core.integration;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.core.env.PropertyResolver;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 接入的第三方平台清单：每个平台的名字、分类、用途、停用后的影响和可配置的字段。
 * 字段的默认值来自 application.yml（也就是 .env），后台填了就用后台的。
 */
public final class IntegrationCatalog {

    private IntegrationCatalog() {
    }

    /** 字段类型：SECRET 是 Key 一类，加密存、只回显后 4 位；TEXT 是地址一类；READONLY 只能在 .env 里改。 */
    public enum Kind { SECRET, TEXT, READONLY }

    /**
     * 一个可配置字段。env 是对应的环境变量名（页面提示"没填就用 .env 的 xxx"）；fallback 从配置里取默认值；
     * bindsSecret 表示这是 Key 发往的地址，改了它要重新填 Key，免得 Key 被发到别的地方。
     */
    public record Field(String name, String label, Kind kind, String env, String placeholder, boolean bindsSecret,
                        BiFunction<LifeProperties, PropertyResolver, String> fallback) {
    }

    /**
     * 一个平台。callName 是调用日志（t_tool_call_log）里记的平台名，用来统计调用量，没记日志的为空；
     * keyOptional 表示不填 Key 也能用（携程问道匿名低频）；defaultEnabled 是第一次建表时的开关；
     * testNote 是"测试"按钮旁边的提醒（比如会占额度）。
     */
    public record Platform(String code, String name, String category, String description, String impact,
                           String callName, boolean keyOptional, List<Field> fields,
                           BiFunction<LifeProperties, PropertyResolver, Boolean> defaultEnabled, String testNote) {

        /** 按名字找字段。 */
        public Optional<Field> field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst();
        }

        /** 有没有必须填的 Key 字段（keyOptional 的平台不算）。 */
        public boolean needsSecret() {
            return !keyOptional && fields.stream().anyMatch(f -> f.kind() == Kind.SECRET);
        }
    }

    public static final List<Platform> PLATFORMS = List.of(
            new Platform("ctrip", "携程问道", "酒店机票",
                    "携程官方给 AI 助手的查询接口：酒店比价、机票、门票规则、美食；不填 Token 也能低频匿名用",
                    "酒店和机票比价里不再出现携程，美食、门票、退改规则少一个来源",
                    "携程", true,
                    List.of(secret("token", "Token", "TRIPAI_API_KEY", (p, e) -> e.getProperty("life.hotel.ctrip.token"))),
                    on(), "会占携程问道的每日额度"),
            new Platform("meituan", "美团 ht-ai", "酒店机票",
                    "美团官方给 AI 助手的查询接口：酒店比价、口碑和评价、机票、大众点评美食",
                    "比价里不再出现美团，酒店口碑重排和美食评分少了大众点评的数据",
                    "美团", false,
                    List.of(secret("token", "Token", "MEITUAN_HT_TOKEN", (p, e) -> e.getProperty("life.hotel.meituan.token"))),
                    on(), "会查一次真实数据，十几秒"),
            new Platform("fliggy", "飞猪 FlyAI", "酒店机票",
                    "阿里官方 FlyAI：酒店、机票、门票查询，通过官方命令行工具调用",
                    "比价、搜酒店、搜门票里不再出现飞猪",
                    "飞猪", false,
                    List.of(secret("api-key", "API Key", "FLYAI_API_KEY", (p, e) -> e.getProperty("life.hotel.fliggy.api-key")),
                            new Field("command", "命令", Kind.READONLY, "FLYAI_COMMAND", "flyai", false,
                                    (p, e) -> e.getProperty("life.hotel.fliggy.command", "flyai"))),
                    on(), "会执行一次官方命令行工具"),
            new Platform("tuniu", "途牛开放平台", "酒店机票",
                    "途牛官方 MCP：机票、酒店、门票查询；额度很紧，每分钟 5 次、每天 50 次",
                    "比价里不再出现途牛，查门票少一个来源",
                    "途牛", false,
                    List.of(secret("api-key", "API Key", "TUNIU_API_KEY", (p, e) -> e.getProperty("life.tuniu.api-key"))),
                    on(), "会占途牛额度 1 次（每分钟 5 次、每天 50 次）"),
            new Platform("rollinggo", "RollingGo", "酒店机票",
                    "道旅官方酒店数据 MCP：实时房型和价格，个人免费",
                    "找酒店和比价时不再用 RollingGo 的实时房价，星络也拿不到它的酒店工具",
                    "RollingGo", false,
                    List.of(secret("api-key", "API Key", "ROLLINGGO_API_KEY", (p, e) -> mcp(p, "rollinggo").getApiKey()),
                            new Field("url", "服务地址", Kind.TEXT, null, "https://mcp.rollinggo.cn", true,
                                    (p, e) -> mcp(p, "rollinggo").getUrl())),
                    on(), "会重新连接一次 MCP 服务"),
            new Platform("amap", "高德 Web 服务", "地图",
                    "查地点、算路线、静态地图、行程地图、首页今日安排的出发时间，和风没配时也用它查天气",
                    "地点搜索、路线、行程地图、出发时间、天气兜底都会失效，影响很大",
                    null, false,
                    List.of(secret("key", "Web 服务 Key", "AMAP_KEY", (p, e) -> p.getAmap().getKey())),
                    on(), "会查一次地址"),
            new Platform("amap-js", "高德 JS 地图", "地图",
                    "页面上可拖动的交互地图；Key 会下发到浏览器，安全密钥只在后端转发时用",
                    "页面上的地图改成静态图片",
                    null, false,
                    List.of(new Field("js-key", "JS Key", Kind.TEXT, "AMAP_JS_KEY", null, false, (p, e) -> p.getAmap().getJsKey()),
                            secret("js-security", "安全密钥", "AMAP_JS_SECURITY", (p, e) -> p.getAmap().getJsSecurity())),
                    on(), "只检查两项是否都填了，不调高德"),
            new Platform("amap-mcp", "高德 MCP", "地图",
                    "高德官方 MCP（路线、距离、周边），用的是上面的 Web 服务 Key，打开后星络可以直接调用高德的工具",
                    "星络拿不到高德 MCP 的工具，地点和路线仍走 Web 服务",
                    null, true, List.of(),
                    (p, e) -> "true".equalsIgnoreCase(mcp(p, "amap").getApiKey()), "会重新连接一次 MCP 服务"),
            new Platform("qweather", "和风天气", "天气",
                    "天气卡片和每日简报的天气：逐小时、降水、空气质量、预警",
                    "天气改用高德（没有逐小时、降水量和预警）",
                    null, false,
                    List.of(new Field("api-host", "API Host", Kind.TEXT, "QWEATHER_API_HOST", "xxx.re.qweatherapi.com", true,
                                    (p, e) -> p.getQweather().getApiHost()),
                            secret("api-key", "API Key", "QWEATHER_API_KEY", (p, e) -> p.getQweather().getApiKey())),
                    on(), "会查一次北京的城市信息"),
            new Platform("bocha", "博查搜索", "搜索",
                    "联网搜攻略、小红书和点评上的游记，按次收费",
                    "星络搜不了攻略，会告诉用户联网搜索没配置",
                    "博查", false,
                    List.of(secret("api-key", "API Key", "BOCHA_API_KEY", (p, e) -> p.getSearch().getBochaApiKey()),
                            new Field("base-url", "接口地址", Kind.TEXT, null, "https://api.bochaai.com", true,
                                    (p, e) -> p.getSearch().getBochaBaseUrl())),
                    on(), "会搜一次，按次计费"),
            new Platform("bark", "Bark 推送", "推送",
                    "iPhone 推送：日程提醒、每日简报；每个用户的设备 Key 在 App 里自己填",
                    "所有 Bark 推送都发不出去，提醒和简报只能在 App 里看",
                    null, true,
                    List.of(new Field("server", "服务地址", Kind.TEXT, "BARK_SERVER", "https://api.day.app", false,
                            (p, e) -> p.getBark().getServer())),
                    on(), "会访问一次服务地址的 /ping")
    );

    /** 按 code 找平台。 */
    public static Optional<Platform> find(String code) {
        return PLATFORMS.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    private static Field secret(String name, String label, String env,
                                BiFunction<LifeProperties, PropertyResolver, String> fallback) {
        return new Field(name, label, Kind.SECRET, env, null, false, fallback);
    }

    private static BiFunction<LifeProperties, PropertyResolver, Boolean> on() {
        return (p, e) -> true;
    }

    private static LifeProperties.McpServer mcp(LifeProperties properties, String name) {
        LifeProperties.McpServer server = properties.getMcp().get(name);
        return server == null ? new LifeProperties.McpServer() : server;
    }
}
