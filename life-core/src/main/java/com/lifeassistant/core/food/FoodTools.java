package com.lifeassistant.core.food;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 找餐厅：同时向美团（大众点评）、携程查，按店名合并成一张表。美食技能和出游技能都用它。 */
@Component
public class FoodTools {

    /** 美团偶尔会卡住几十秒，超过这个时间就不等了 */
    private static final long DEADLINE_SECONDS = 35;
    private static final int MAX_SHOPS = 10;
    private static final int MAX_RAW = 1500;

    private final List<RestaurantSource> sources;

    public FoodTools(List<RestaurantSource> sources) {
        this.sources = sources;
    }

    /**
     * 合并后的一家餐厅：评分优先用大众点评（美团返回）的，人均优先大众点评，招牌菜、地址、营业时间优先携程；
     * dianpingUrl 是点评店铺页，platforms 是哪些平台推荐了它。display 是表格里直接用的店名：有点评链接时是 Markdown 链接，
     * 没有就是纯店名，免得模型自己拼链接时串到别的店。
     */
    public record Restaurant(String display, String name, Double rating, String ratingFrom, Integer avgPrice, String dishes,
                             String location, String hours, String highlight, String dianpingUrl,
                             List<String> platforms) {
    }

    @Tool(description = """
            找餐厅：同时查美团（大众点评的评分和店铺链接）和携程（美食林的招牌菜、营业时间），按店名合并，
            返回评分、人均、招牌菜、位置、营业时间和点评链接；两个平台都推荐的排前面。展示店名时原样用 display 字段。某个平台没解析出店铺时，
            原文在 raw 里。查一次要十秒左右，调用前先告诉用户。""")
    public Map<String, Object> findRestaurants(
            @ToolParam(description = "城市，例如：西安") String city,
            @ToolParam(description = "位置：地标、商圈、酒店或景点，例如：钟楼；不限位置传空", required = false) String near,
            @ToolParam(description = "菜系或想吃的，例如：陕菜、火锅、不辣的；不限传空", required = false) String cuisine,
            @ToolParam(description = "人均上限（元），不限传空", required = false) Integer maxPrice,
            @ToolParam(description = "场景，例如：带孩子、约会、宴请长辈、一个人；没有传空", required = false) String scene) {
        if (city == null || city.isBlank()) {
            return Map.of("error", "缺少城市");
        }
        RestaurantSource.Query query = new RestaurantSource.Query(city.trim(), near, cuisine, maxPrice, scene);
        List<RestaurantSource> active = sources.stream().filter(RestaurantSource::available).toList();
        List<RestaurantSource.Result> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RestaurantSource.Result>> futures = active.stream()
                    .map(s -> executor.submit(() -> s.search(query))).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(futures.get(i), active.get(i).platform(), deadline));
            }
            executor.shutdownNow();
        }
        Map<String, Object> platforms = new LinkedHashMap<>();
        Map<String, String> raw = new LinkedHashMap<>();
        for (RestaurantSource.Result r : results) {
            platforms.put(r.platform(), r.status().equals("OK") ? "OK，" + r.shops().size() + " 家"
                    : r.status() + (r.note() == null ? "" : "：" + r.note()));
            if (r.raw() != null) {
                raw.put(r.platform(), r.raw().length() > MAX_RAW ? r.raw().substring(0, MAX_RAW) + "…" : r.raw());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query.shortQuestion());
        out.put("platforms", platforms);
        out.put("restaurants", merge(results));
        if (!raw.isEmpty()) {
            out.put("raw", raw);
        }
        return out;
    }

    /** 按店名合并各平台结果；两个平台都有的排前面，其次按评分从高到低，截取前 MAX_SHOPS 家。 */
    static List<Restaurant> merge(List<RestaurantSource.Result> results) {
        // 店名 -> (平台 -> 该平台的这家店)，同一平台重复出现只留第一次
        Map<String, Map<String, RestaurantSource.Shop>> byShop = new LinkedHashMap<>();
        for (RestaurantSource.Result result : results) {
            for (RestaurantSource.Shop shop : result.shops()) {
                String key = byShop.keySet().stream().filter(k -> sameShop(k, shop.name())).findFirst()
                        .orElse(shop.name());
                byShop.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(result.platform(), shop);
            }
        }
        List<Restaurant> merged = new ArrayList<>();
        for (Map<String, RestaurantSource.Shop> byPlatform : byShop.values()) {
            List<String> from = List.copyOf(byPlatform.keySet());
            RestaurantSource.Shop dianping = byPlatform.get("美团");
            RestaurantSource.Shop ctrip = byPlatform.get("携程");
            Double rating = first(dianping == null ? null : dianping.rating(), ctrip == null ? null : ctrip.rating());
            String ratingFrom = rating == null ? null
                    : dianping != null && rating.equals(dianping.rating()) ? "大众点评" : "携程";
            String name = byPlatform.values().iterator().next().name();
            String url = dianping == null ? null : dianping.url();
            merged.add(new Restaurant(url == null ? name : "[" + name + "](" + url + ")", name, rating, ratingFrom,
                    first(dianping == null ? null : dianping.avgPrice(), ctrip == null ? null : ctrip.avgPrice()),
                    ctrip == null ? null : ctrip.dishes(),
                    first(ctrip == null ? null : ctrip.location(), dianping == null ? null : dianping.location()),
                    ctrip == null ? null : ctrip.hours(),
                    first(dianping == null ? null : dianping.highlight(), ctrip == null ? null : ctrip.highlight()),
                    url, from));
        }
        merged.sort(Comparator.comparing((Restaurant r) -> r.platforms().size(), Comparator.reverseOrder())
                .thenComparing(Restaurant::rating, Comparator.nullsLast(Comparator.reverseOrder())));
        return merged.size() > MAX_SHOPS ? merged.subList(0, MAX_SHOPS) : merged;
    }

    /**
     * 判断两个店名是不是同一家：去掉标点后完全相同，或者品牌名（第一个分隔符前）和分店名（最后一对括号里）都相同。
     * 例如"西安饭庄·非遗陕菜（钟楼店）"和"西安饭庄·非遗陕菜(钟楼店)"。
     */
    static boolean sameShop(String a, String b) {
        if (normalize(a).equals(normalize(b))) {
            return true;
        }
        String branchA = branch(a);
        return branchA != null && branchA.equals(branch(b)) && normalize(brand(a)).equals(normalize(brand(b)));
    }

    private static String normalize(String name) {
        return name.replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]", "").toLowerCase();
    }

    private static String brand(String name) {
        return name.split("[·・(（\\-—|｜]", 2)[0];
    }

    private static String branch(String name) {
        int open = Math.max(name.lastIndexOf('('), name.lastIndexOf('（'));
        int close = Math.max(name.lastIndexOf(')'), name.lastIndexOf('）'));
        return open >= 0 && close > open ? normalize(name.substring(open + 1, close)) : null;
    }

    private static <T> T first(T a, T b) {
        return a != null ? a : b;
    }

    /** 在总截止时间前取结果，超时或出错记为失败。 */
    private static RestaurantSource.Result await(Future<RestaurantSource.Result> future, String platform, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RestaurantSource.Result.failed(platform, "查询被中断");
        } catch (Exception e) {
            future.cancel(true);
            return RestaurantSource.Result.failed(platform, "查询超时");
        }
    }
}
