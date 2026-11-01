package com.lifeassistant.skill.travel.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.cache.QueryCache;
import com.lifeassistant.skill.travel.platform.TuniuClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 途牛门票（开放平台 MCP 的 query_cheapest_tickets）：按景点名查票种、起价、退改、入园方式、要提前多久订。
 * 价格是可售日期里的最低价，要标"起"；没有预订链接。每天只有 50 次额度，点了才查，结果缓存 2 小时。
 */
@Component
@RequiredArgsConstructor
public class TuniuTickets {

    private final TuniuClient client;
    private final QueryCache queryCache;

    /**
     * 一个票种：type 是成人、儿童、学生、老人、优待、通用（不限人群），看不出来为空，category 是途牛的分类（景点门票、园内项目……），
     * price 是起价（元），refund 是退改（随时退、有条件退……），advance 是要提前多久订，open 是开放时间，
     * saleFrom、saleTo 是可售日期。
     */
    public record Ticket(String name, String type, String category, Integer price, String refund, String enter,
                         String advance, String open, String saleFrom, String saleTo) {
    }

    /** 这个景点在途牛上的票种，门票在前、成人票在前，再按价格。 */
    public List<Ticket> tickets(String spot, LocalDate date, boolean refresh) {
        if (!client.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "途牛没有配置，查不了门票");
        }
        String name = TicketSearch.shortName(spot);
        // "秦始皇帝陵博物院（兵马俑）"：途牛上可能叫括号里的名字，正式名查不到再用它查一次
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[（(]([^）)]{2,10})[）)]").matcher(spot);
        String alias = m.find() ? m.group(1) : null;
        return queryCache.get("tuniu-tickets", name + "|" + date, Duration.ofHours(2), refresh,
                queryCache.listOf(Ticket.class), () -> {
                    List<Ticket> list = parse(callTool(args(name, date)));
                    return list.isEmpty() && alias != null ? parse(callTool(args(alias, date))) : list;
                }, list -> !list.isEmpty());
    }

    private static Map<String, Object> args(String name, LocalDate date) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scenic_name", name);
        if (date != null && !date.isBefore(LocalDate.now())) {
            args.put("depart_date", date.toString());
        }
        return args;
    }

    private JsonNode callTool(Map<String, Object> args) {
        try {
            return client.call("ticket", "query_cheapest_tickets", args);
        } catch (IllegalStateException e) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, e.getMessage());
        }
    }

    static List<Ticket> parse(JsonNode root) {
        List<Ticket> out = new ArrayList<>();
        for (JsonNode t : root.path("tickets")) {
            String name = text(t, "resName");
            if (name == null) {
                continue;
            }
            name = name.replaceAll("_标准价$", "").replaceAll("^[-\\s]+", "").replace("()", "");
            String person = text(t, "personTypeName");
            String type = person == null ? TicketSearch.skuType(name) : switch (person) {
                case "成人票" -> "成人";
                case "儿童票" -> "儿童";
                case "学生票" -> "学生";
                case "老人票", "长者票" -> "老人";
                case "不限人群" -> "通用";
                default -> TicketSearch.skuType(name);
            };
            JsonNode open = t.path("scenicOpenInfo");
            String openText = text(open, "showOpenStartTime") != null && text(open, "showOpenEndTime") != null
                    ? text(open, "showOpenStartTime") + "–" + text(open, "showOpenEndTime") : null;
            out.add(new Ticket(name, type, text(t, "firstType"), price(text(t, "startPrice")), text(t, "lossName"),
                    text(t, "enterTypeName"), advance(t), openText, text(t, "startDate"), text(t, "endDate")));
        }
        out.sort(Comparator.comparing((Ticket t) -> !"景点门票".equals(t.category()))
                .thenComparing(t -> !"成人".equals(t.type()))
                .thenComparing(Ticket::price, Comparator.nullsLast(Integer::compare)));
        return out.stream().limit(20).toList();
    }

    /** advanceBookDay、advanceBookHour 换成"可当天订"或"需提前 N 天订"。 */
    private static String advance(JsonNode t) {
        int days = t.path("advanceBookDay").asInt(0);
        int hours = t.path("advanceBookHour").asInt(0);
        if (days > 0) {
            return "需提前 " + days + " 天订";
        }
        return hours > 0 ? "需提前 " + hours + " 小时订" : "可当天订";
    }

    /** "10.0" 取整数元。 */
    private static Integer price(String text) {
        try {
            return text == null ? null : (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
