package com.lifeassistant.api.web.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.core.integration.IntegrationCatalog;
import com.lifeassistant.core.integration.IntegrationSettings;
import com.lifeassistant.core.mcp.McpServerRegistry;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.core.platform.CtripWendaoClient;
import com.lifeassistant.core.platform.MeituanHtClient;
import com.lifeassistant.core.search.BochaSearchProvider;
import com.lifeassistant.core.weather.QWeatherProvider;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import com.lifeassistant.skill.travel.platform.TuniuClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Supplier;

/**
 * 后台"测试"按钮：用当前生效的配置给平台发一个最便宜的请求，返回成败、耗时和一句说明。
 * 走的是业务里同一个客户端，测通了业务就能用；错误信息里如果带了 Key（比如高德的 Key 在网址里）会先抹掉。
 */
@Component
@RequiredArgsConstructor
class IntegrationTester {

    private final IntegrationSettings settings;
    private final CtripWendaoClient ctrip;
    private final MeituanHtClient meituan;
    private final FliggyCli fliggy;
    private final TuniuClient tuniu;
    private final McpServerRegistry mcp;
    private final AmapPlaceService amap;
    private final QWeatherProvider qweather;
    private final BochaSearchProvider bocha;
    private final RestClient.Builder externalRestClientBuilder;

    /** 测试结果：ok 是否测通，ms 耗时，message 给管理员看的一句话。 */
    record Result(boolean ok, long ms, String message) {
    }

    /** 测一个平台；停用的平台不测，直接返回提示。 */
    Result test(IntegrationCatalog.Platform platform) {
        String code = platform.code();
        if (!settings.enabled(code)) {
            return new Result(false, 0, "这个平台已停用，启用后再测");
        }
        long start = System.nanoTime();
        try {
            String message = run(code);
            return new Result(true, elapsed(start), message);
        } catch (RuntimeException e) {
            return new Result(false, elapsed(start), clean(platform, e));
        }
    }

    private String run(String code) {
        return switch (code) {
            case "ctrip" -> "携程回复了 " + length(ctrip.query("北京故宫的开放时间，一句话回答")) + " 个字";
            case "meituan" -> {
                require(meituan.available(), "没有 Token");
                yield "美团回复了 " + length(meituan.query("北京", "北京王府井附近的酒店，列 1 家", "王府井附近酒店")) + " 个字";
            }
            case "fliggy" -> {
                require(fliggy.available(), "没有 API Key");
                JsonNode root = fliggy.run(List.of("search-hotel", "--key-words", "全季", "--dest-name", "北京"));
                yield "飞猪返回 " + root.path("data").path("itemList").size() + " 家酒店";
            }
            case "tuniu" -> {
                require(tuniu.available(), "没有 API Key");
                yield "途牛连上了，有 " + tuniu.ping() + " 个工具";
            }
            case "rollinggo", "amap-mcp" -> {
                String server = code.equals("amap-mcp") ? "amap" : code;
                int tools = mcp.reconnect(server);
                require(tools >= 0, code.equals("amap-mcp") ? "高德 Web 服务的 Key 没配或已停用" : "没有 API Key 或地址");
                require(tools > 0, "连不上，详细原因看后端日志");
                yield "连上了，有 " + tools + " 个工具";
            }
            case "amap" -> {
                require(amap.available(), "没有 Key");
                double[] p = amap.center("北京市东城区天安门").orElseThrow(
                        () -> new IllegalStateException("高德没返回坐标，检查 Key 是否有效、是否开通了 Web 服务"));
                yield "查到天安门的坐标 " + p[0] + "," + p[1];
            }
            case "amap-js" -> {
                require(StringUtils.hasText(settings.value("amap-js", "js-key"))
                        && StringUtils.hasText(settings.value("amap-js", "js-security")), "JS Key 和安全密钥要都填");
                yield "两项都填了，页面上会显示可拖动地图（Key 是否有效要打开行程地图看）";
            }
            case "qweather" -> {
                require(qweather.available(), "API Host 和 API Key 要都填");
                yield "查到 " + qweather.resolve("北京").name();
            }
            case "bocha" -> {
                require(bocha.available(), "没有 API Key");
                yield "搜到 " + bocha.search("北京天气", List.of(), 1, false).size() + " 条结果";
            }
            case "bark" -> {
                String server = settings.value("bark", "server");
                require(StringUtils.hasText(server), "没有服务地址");
                String body = externalRestClientBuilder.clone().baseUrl(server).build()
                        .get().uri("/ping").retrieve().body(String.class);
                yield "服务地址能访问：" + abbreviate(body);
            }
            default -> throw new IllegalStateException("这个平台还不支持测试");
        };
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** 错误信息：抹掉这个平台（和高德）的 Key，截短。 */
    private String clean(IntegrationCatalog.Platform platform, RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        for (IntegrationCatalog.Field f : platform.fields()) {
            if (f.kind() == IntegrationCatalog.Kind.SECRET) {
                message = mask(message, () -> settings.rawValue(platform.code(), f.name()));
            }
        }
        message = mask(message, () -> settings.rawValue("amap", "key"));
        return abbreviate(message);
    }

    private static String mask(String text, Supplier<String> secret) {
        String s = secret.get();
        return StringUtils.hasText(s) && s.length() >= 4 ? text.replace(s, "***") : text;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
