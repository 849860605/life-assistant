package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.skill.travel.platform.TuniuClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 途牛查机票（开放平台 MCP 的 searchLowestPriceFlight）：一次固定返回 10 班，票面价和税费分开给，带机型、航站楼。
 * 搜索结果里没有预订链接；最低价可能是带条件的特惠（如长者价），要看舱位详情才知道。额度很紧，一次比价只查一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuniuFlightSource implements FlightPriceSource {

    private final TuniuClient client;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    /** 一个舱位：票面价、税费、购买条件（如"青年特惠"）、限制说明、行李额、餐食、退改说明、剩余座位。 */
    public record Cabin(Integer price, Integer tax, String condition, List<String> limits, String baggage, String meal,
                        String refund, String seats, String cabinClass) {
    }

    @Override
    public String platform() {
        return "途牛";
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public Result search(String origin, String destination, LocalDate date) {
        try {
            return parse(client.call("flight", "searchLowestPriceFlight", Map.of("departureCityName", origin,
                    "arrivalCityName", destination, "departureDate", date.toString())));
        } catch (RuntimeException e) {
            log.warn("途牛查机票失败：{}", e.getMessage());
            return Result.failed(platform(), e.getMessage());
        }
    }

    /** 某个航班的全部舱位（价格从低到高），按需查，缓存 30 分钟。 */
    public List<Cabin> cabins(String origin, String destination, LocalDate date, String flightNo, boolean refresh) {
        return queryCache.get("tuniu-cabins", origin + "|" + destination + "|" + date + "|" + flightNo,
                java.time.Duration.ofMinutes(30), refresh, queryCache.listOf(Cabin.class),
                () -> loadCabins(origin, destination, date, flightNo), list -> !list.isEmpty());
    }

    private List<Cabin> loadCabins(String origin, String destination, LocalDate date, String flightNo) {
        JsonNode root = client.call("flight", "multiCabinDetails", Map.of("departureCityName", origin,
                "arrivalCityName", destination, "departureDate", date.toString(), "flightNo", flightNo));
        List<Cabin> out = new ArrayList<>();
        for (JsonNode c : root.path("cabinInfo")) {
            List<String> limits = new ArrayList<>();
            for (JsonNode l : c.path("saleControl")) {
                String t = text(l);
                if (t != null) {
                    limits.add(t);
                }
            }
            out.add(new Cabin(integer(text(c.path("basePrice"))), integer(text(c.path("totalTax"))),
                    text(c.path("buyCondition")), limits, text(c.path("baggageInfo")), text(c.path("mealCode")),
                    text(c.path("refundChangeRule")), text(c.path("remainingSeats")), text(c.path("cabinClass"))));
        }
        out.sort(java.util.Comparator.comparing(Cabin::price, java.util.Comparator.nullsLast(Integer::compare)));
        return out;
    }

    static Result parse(JsonNode root) {
        if (!root.path("successCode").asBoolean(true) && !root.has("data")) {
            return Result.failed("途牛", "途牛没查到：" + root.path("msg").asText(""));
        }
        List<Offer> offers = new ArrayList<>();
        for (JsonNode f : root.path("data")) {
            String no = text(f.path("flightNumber"));
            if (no == null) {
                continue;
            }
            String airline = text(f.path("airlineCompany"));
            if (airline != null && airline.matches(".*[|｜].*")) {
                airline = airline.replaceAll(".*[|｜]\\s*", "");
            }
            String type = text(f.path("type"));
            offers.add(new Offer(no, airline, time(text(f.path("departureTime"))), time(text(f.path("arrivalTime"))),
                    join(text(f.path("departureAirport")), text(f.path("departureTerminal"))),
                    join(text(f.path("arrivalAirport")), text(f.path("arrivalTerminal"))),
                    integer(text(f.path("basePrice"))), null, type != null && !type.contains("直飞") ? type : null,
                    FlightPriceSource.minutes(text(f.path("totalDuration"))), aircraft(text(f.path("craftType"))),
                    null, null, null, integer(text(f.path("totalTax")))));
        }
        return Result.of("途牛", offers, null);
    }

    /** 机型写法整理："A320_186" → "A320"，"919" → "C919"，"7MT" → "737MAX"。 */
    static String aircraft(String craft) {
        if (craft == null) {
            return null;
        }
        String c = craft.replaceAll("_.*$", "");
        return switch (c) {
            case "919" -> "C919";
            case "7MT", "7M8" -> "737MAX";
            case "738" -> "737-800";
            case "32S", "320" -> "A320";
            case "321" -> "A321";
            case "333" -> "A330";
            default -> c;
        };
    }

    private static String join(String airport, String terminal) {
        return airport == null ? null : airport + (terminal == null ? "" : terminal);
    }

    /** "2026-11-20 09:35" 取 09:35。 */
    private static String time(String dateTime) {
        return dateTime == null ? null : dateTime.length() >= 16 ? dateTime.substring(11, 16) : dateTime;
    }

    /** 途牛的空值有时写成字符串 "null"。 */
    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String t = n.asText().trim();
        return t.isEmpty() || "null".equals(t) ? null : t;
    }

    private static Integer integer(String s) {
        try {
            return s == null ? null : (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
