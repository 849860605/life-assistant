package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 酒店比价页的候选酒店：在飞猪按"某地附近"找，带入住日期的价格、评分、档次、坐标和照片。
 * 按距离、评分各查一次再合并（飞猪一次只给 10 家）。飞猪返回里有佣金字段，这里不读、不参与排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelSearch {

    private static final List<String> SORTS = List.of("distance_asc", "rate_desc", "price_asc");
    private static final int DEFAULT_RADIUS = 5000;

    private final FliggyCli cli;
    private final AmapPlaceService places;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    /**
     * 一家候选：price 是这几天在飞猪的每晚最低价（元），rating 是飞猪评分，star 是档次（豪华型、高档型……），
     * renovated 是装修年份，near 是飞猪标的附近地标，url 是飞猪预订页；distance 是离搜索地标的直线距离（米）。
     */
    public record Hotel(String id, String name, String address, Double lng, Double lat, String photo, Integer price,
                        Double rating, String star, String renovated, String near, String url, Integer distance) {

        Hotel withDistance(Integer meters) {
            return new Hotel(id, name, address, lng, lat, photo, price, rating, star, renovated, near, url, meters);
        }
    }

    /**
     * 搜索结果：查询条件和候选（离地标 radius 米以内，按距离排），anchorLng、anchorLat 是地标在高德的位置（找不到为空，
     * 这时不按距离筛），queriedAt 是查询时间。
     */
    public record Result(String city, String near, Double anchorLng, Double anchorLat, int radius, String checkIn,
                         String checkOut, List<Hotel> hotels, String queriedAt, String note) {
    }

    /**
     * @param near   附近的地标（景点、商圈），空表示整个城市
     * @param type   hotel、homestay、inn，空表示不限
     * @param stars  星级 1-5 逗号分隔，空表示不限
     */
    public Result search(String city, String near, LocalDate checkIn, LocalDate checkOut, String type, String stars,
                         Integer maxPrice, Integer radius, boolean refresh) {
        return queryCache.get("hotels", String.join("|", city, String.valueOf(near), String.valueOf(checkIn),
                        String.valueOf(checkOut), String.valueOf(type), String.valueOf(stars), String.valueOf(maxPrice),
                        String.valueOf(radius)), java.time.Duration.ofMinutes(30), refresh, Result.class,
                () -> load(city, near, checkIn, checkOut, type, stars, maxPrice, radius), r -> !r.hotels().isEmpty());
    }

    private Result load(String city, String near, LocalDate checkIn, LocalDate checkOut, String type, String stars,
                        Integer maxPrice, Integer radius) {
        if (!cli.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "飞猪没有配置，找不了酒店");
        }
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "入住、退房日期不对");
        }
        Map<String, Hotel> merged = new LinkedHashMap<>();
        String error = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> futures = SORTS.stream().map(sort -> executor.submit(() ->
                    cli.run(args(city, near, checkIn, checkOut, type, stars, maxPrice, sort)))).toList();
            for (Future<JsonNode> f : futures) {
                try {
                    parse(f.get()).forEach(h -> merged.putIfAbsent(h.id() == null ? h.name() : h.id(), h));
                } catch (ExecutionException e) {
                    error = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "查询被中断");
        }
        if (merged.isEmpty() && error != null) {
            log.warn("飞猪找酒店失败：{}", error);
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "飞猪暂时查不到酒店，稍后再试");
        }
        // 飞猪的"附近"不严格，按高德上地标的位置算直线距离再筛
        int within = radius == null ? DEFAULT_RADIUS : Math.max(500, Math.min(radius, 30000));
        double[] anchor = near == null || near.isBlank() ? null : places.find(near.trim(), city)
                .filter(p -> p.lng() != null).map(p -> new double[]{p.lng(), p.lat()}).orElse(null);
        List<Hotel> hotels = new ArrayList<>(merged.values());
        if (anchor != null) {
            hotels = hotels.stream().map(h -> h.withDistance(h.lng() == null ? null
                            : (int) Math.round(distanceKm(anchor[1], anchor[0], h.lat(), h.lng()) * 1000)))
                    .filter(h -> h.distance() != null && h.distance() <= within)
                    .sorted(java.util.Comparator.comparing(Hotel::distance)).toList();
        }
        return new Result(city, near, anchor == null ? null : anchor[0], anchor == null ? null : anchor[1], within,
                checkIn.toString(), checkOut.toString(), hotels, CardHub.now(),
                hotels.isEmpty() ? "这附近没找到符合条件的酒店，放宽距离或价格试试"
                        : anchor == null && near != null && !near.isBlank() ? "高德上没找到「" + near + "」，没按距离筛" : null);
    }

    private static List<String> args(String city, String near, LocalDate in, LocalDate out, String type, String stars,
                                     Integer maxPrice, String sort) {
        List<String> args = new ArrayList<>(List.of("search-hotel", "--dest-name", city,
                "--check-in-date", in.toString(), "--check-out-date", out.toString(), "--sort", sort));
        if (near != null && !near.isBlank()) {
            args.addAll(List.of("--poi-name", near.trim()));
        }
        if (type != null && type.matches("hotel|homestay|inn")) {
            args.addAll(List.of("--hotel-types", type));
        }
        if (stars != null && stars.matches("[1-5](,[1-5])*")) {
            args.addAll(List.of("--hotel-stars", stars));
        }
        if (maxPrice != null && maxPrice > 0) {
            args.addAll(List.of("--max-price", String.valueOf(maxPrice)));
        }
        return args;
    }

    static List<Hotel> parse(JsonNode root) {
        List<Hotel> out = new ArrayList<>();
        for (JsonNode h : root.path("data").path("itemList")) {
            String name = h.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            out.add(new Hotel(text(h, "shId"), name, text(h, "address"), number(h, "longitude"), number(h, "latitude"),
                    https(text(h, "mainPic")),
                    price(text(h, "price")), rating(number(h, "rate")), text(h, "star"), year(text(h, "decorationTime")),
                    text(h, "interestsPoi"), text(h, "detailUrl"), null));
        }
        return out;
    }

    /** 飞猪没有评分时给 0，当成没有。 */
    private static Double rating(Double r) {
        return r == null || r <= 0 ? null : r;
    }

    /** "2024-01-01" 取年份。 */
    private static String year(String text) {
        return text == null || text.length() < 4 ? null : text.substring(0, 4);
    }

    /** 球面距离（公里）。 */
    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /** 只要 https 的图片地址（页面上直接显示）。 */
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

    /** "¥728" 取 728。 */
    private static Integer price(String text) {
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
