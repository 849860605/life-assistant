package com.lifeassistant.skill.travel.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 景点和门票（飞猪）：search-poi 查景点（等级、榜单、是否免费、简介、坐标），keyword-search "某景点门票" 查门票商品
 * （价格、票种、预订链接）。成人价、儿童价按票种名称挑，挑不出就不给，不拿学生票、优待票冒充成人价。
 * 飞猪返回里有佣金字段，这里不读、不参与排序。结果缓存 1 小时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSearch {

    /** 飞猪景点分类（命令行认的英文） */
    public static final Set<String> CATEGORIES = Set.of("historic site", "museum", "temple", "nature", "landmark",
            "theme park", "garden", "ancient town", "zoo", "aquarium", "market", "hot spring");
    /** 不是成人全价票的票种 */
    private static final Pattern DISCOUNT = Pattern.compile("儿童|学生|老人|长者|优待|半价|退役|军人|残疾|教师|免费|亲子|家庭|青少年|婴|幼|周岁");
    /** 标题里有这些的是一日游、讲解、套餐，不是单纯门票 */
    private static final Pattern BUNDLE = Pattern.compile("一日游|跟团|包车|讲解|精讲|导游|导览|套餐|联票|接送|拼团|小团|私家团|半日游|往返|演出|耳麦|直通车|大巴|班车|专线|车票");
    /** 票种名称能看出是哪类票的：成人、儿童、学生、老人；时段票（几点到几点）不带优惠字样的按成人全价票算 */
    private static final Pattern ADULT = Pattern.compile("成人|全价|标准|普通|大门票|\\d{1,2}[点:：]\\d{0,2}\\s*[-~至到]");
    /** 同时最多跑几个飞猪命令，跑太多会返回空结果 */
    private final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(3);
    private static final int MAX_PRODUCTS = 6;

    private final FliggyCli cli;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    /**
     * 一个景点：level 是 A 级（5 表示 5A，没有为空），rank 是飞猪榜单说明，free 为 true 表示免费景点（飞猪标的），
     * url 是飞猪景点页。
     */
    public record Spot(String id, String name, String category, String address, String description, Double lng,
                       Double lat, String photo, Integer level, String rank, Boolean free, String url) {
    }

    /** 一个票种：名称（含时段）、价格、类别（成人、儿童、学生、老人、优待，看不出来为空）。 */
    public record Sku(String name, Integer price, String type) {
    }

    /**
     * 一个门票商品：bundle 为 true 表示含讲解、一日游、套餐之类，不是单纯门票；adult、child 是从票种名称里能看出来的
     * 成人价、儿童价（看不出来为空，不拿商品标价顶替）；price 是商品标价（常常是某个优惠票种或起价）。
     */
    public record Product(String title, Integer price, Integer adult, Integer child, boolean bundle, List<Sku> skus,
                          String url) {
    }

    public List<Spot> spots(String city, String keyword, String category) {
        return spots(city, keyword, category, false);
    }

    /** 景点（缓存 12 小时，refresh 为 true 时重新查）。 */
    public List<Spot> spots(String city, String keyword, String category, boolean refresh) {
        if (!cli.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "飞猪没有配置，查不了景点");
        }
        List<String> args = new ArrayList<>(List.of("search-poi", "--city-name", city));
        if (keyword != null && !keyword.isBlank()) {
            args.addAll(List.of("--keyword", keyword.trim()));
        }
        if (category != null && CATEGORIES.contains(category)) {
            args.addAll(List.of("--category", category));
        }
        return queryCache.get("spots", String.join(" ", args), java.time.Duration.ofHours(12), refresh,
                queryCache.listOf(Spot.class), () -> parseSpots(run(args)), list -> !list.isEmpty());
    }

    /** 某个景点的门票商品：单纯门票排前面，再按成人价（没有就按标价）从低到高。 */
    public List<Product> tickets(String city, String spot) {
        return tickets(city, spot, false);
    }

    /** 门票商品（缓存 12 小时，refresh 为 true 时重新查）。 */
    public List<Product> tickets(String city, String spot, boolean refresh) {
        if (!cli.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "飞猪没有配置，查不了门票");
        }
        String name = shortName(spot);
        if (name.isBlank()) {
            return List.of();
        }
        return queryCache.get("spot-tickets", city + "|" + name, java.time.Duration.ofHours(12), refresh,
                queryCache.listOf(Product.class), () -> parseProducts(
                        run(List.of("keyword-search", "--query", (city == null ? "" : city) + name + "门票")), name),
                list -> !list.isEmpty());
    }

    static List<Spot> parseSpots(JsonNode root) {
        List<Spot> out = new ArrayList<>();
        for (JsonNode p : root.path("data").path("itemList")) {
            String name = text(p, "name");
            if (name == null) {
                continue;
            }
            String desc = text(p, "description");
            Integer level = p.path("poiLevel").isNumber() || text(p, "poiLevel") != null ? toInt(text(p, "poiLevel")) : null;
            String free = text(p, "freePoiStatus");
            out.add(new Spot(text(p, "id"), name, text(p, "category"), text(p, "address"),
                    desc == null ? null : desc.length() > 140 ? desc.substring(0, 140) + "…" : desc,
                    number(p, "longitude"), number(p, "latitude"), https(text(p, "mainPic")),
                    level == null || level <= 0 ? null : level, text(p, "listRank"),
                    free == null ? null : "FREE".equals(free), text(p, "jumpUrl")));
        }
        return out;
    }

    static List<Product> parseProducts(JsonNode root, String spot) {
        List<Product> out = new ArrayList<>();
        for (JsonNode item : root.path("data").path("itemList")) {
            JsonNode info = item.path("info");
            String title = text(info, "title");
            String pk = text(info, "pk");
            // 只要商品（vacation_），而且标题里有这个景点
            if (title == null || pk == null || !pk.startsWith("vacation_") || !title.replace(" ", "").contains(spot)) {
                continue;
            }
            List<Sku> skus = new ArrayList<>();
            JsonNode struct = info.path("skuCommissionStruct");
            for (Iterator<Map.Entry<String, JsonNode>> it = struct.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                skus.add(new Sku(e.getKey(), toInt(e.getValue().path("price").asText(null)), skuType(e.getKey())));
            }
            Integer adult = skus.stream().filter(s -> s.price() != null && !DISCOUNT.matcher(s.name()).find()
                            && ADULT.matcher(s.name()).find())
                    .map(Sku::price).min(Integer::compare).orElse(null);
            Integer child = skus.stream().filter(s -> s.price() != null && s.name().contains("儿童"))
                    .map(Sku::price).min(Integer::compare).orElse(null);
            boolean bundle = BUNDLE.matcher(title).find();
            out.add(new Product(title, toInt(text(info, "price")), adult, child, bundle,
                    skus.stream().limit(12).toList(), text(info, "jumpUrl")));
        }
        // 单纯门票在前，其中看得出成人价的在前，再按价格
        out.sort(Comparator.comparing(Product::bundle).thenComparing(p -> p.adult() == null)
                .thenComparing(p -> p.adult() != null ? p.adult() : p.price() != null ? p.price() : Integer.MAX_VALUE));
        return out.stream().limit(MAX_PRODUCTS).toList();
    }

    /** "秦始皇兵马俑博物馆（请讲解）" → "秦始皇兵马俑博物馆"；"大雁塔 · 大慈恩寺" → "大雁塔"。 */
    public static String shortName(String name) {
        return name == null ? "" : name.replaceAll("[（(].*?[）)]", "").split("\\s*[·・/]\\s*")[0].trim();
    }

    /** 跑飞猪命令，同时最多 3 个。 */
    private JsonNode run(List<String> args) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询被中断");
        }
        try {
            return cli.run(args);
        } finally {
            gate.release();
        }
    }

    /** 票种的类别：成人、儿童、学生、老人、优待（其他优惠票）；看不出来为空。 */
    public static String skuType(String name) {
        if (name == null) {
            return null;
        }
        if (name.contains("儿童") || name.contains("青少年")) {
            return "儿童";
        }
        if (name.contains("学生")) {
            return "学生";
        }
        if (name.matches(".*(老人|长者|老年|60周岁|65周岁|70周岁).*")) {
            return "老人";
        }
        if (DISCOUNT.matcher(name).find()) {
            return "优待";
        }
        return ADULT.matcher(name).find() ? "成人" : null;
    }

    private static String https(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static Double number(JsonNode node, String field) {
        try {
            String t = text(node, field);
            return t == null ? null : Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "¥77.46"、"119" 取整数元。 */
    private static Integer toInt(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll("[^0-9.]", "");
        try {
            return digits.isEmpty() ? null : (int) Math.round(Double.parseDouble(digits));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
