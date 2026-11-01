package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.skill.travel.platform.TuniuClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 途牛查酒店价格（开放平台 MCP 的 tuniuHotelSearch，按酒店名关键词搜）：给每晚最低价起、评分和最便宜房型的餐食、退改。
 * 搜索结果里没有预订链接；途牛每天只有 50 次额度（所有 Key 共用），一家酒店查一次，结果缓存 30 分钟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuniuPriceSource implements HotelPriceSource {

    private final TuniuClient client;

    @Override
    public String platform() {
        return "途牛";
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public Quote quote(String hotel, String city, LocalDate checkIn, LocalDate checkOut) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (StringUtils.hasText(city)) {
            args.put("cityName", city);
        }
        args.put("keyword", hotel);
        if (checkIn != null && checkOut != null) {
            args.put("checkIn", checkIn.toString());
            args.put("checkOut", checkOut.toString());
        }
        try {
            return parse(client.call("hotel", "tuniuHotelSearch", args), hotel);
        } catch (RuntimeException e) {
            log.warn("途牛查酒店失败：{}", e.getMessage());
            return Quote.failed(platform(), e.getMessage());
        }
    }

    /** 从 hotels 里挑名字和查询名一致或互相包含的一条；对不上就算没找到，不拿别的酒店顶替。 */
    static Quote parse(JsonNode root, String hotel) {
        JsonNode hotels = root.path("hotels");
        if (!hotels.isArray() || hotels.isEmpty()) {
            return Quote.failed("途牛", "途牛没搜到这家酒店");
        }
        String want = normalize(hotel);
        JsonNode picked = null;
        for (JsonNode h : hotels) {
            String name = normalize(h.path("hotelName").asText());
            if (!name.isEmpty() && (name.equals(want) || name.contains(want) || want.contains(name))) {
                picked = h;
                break;
            }
        }
        if (picked == null) {
            return Quote.failed("途牛", "途牛上没找到同名酒店");
        }
        Integer price = picked.path("lowestPrice").isNumber() ? picked.path("lowestPrice").asInt() : null;
        List<String> rooms = new ArrayList<>();
        String room = join(" · ", text(picked, "roomName"), text(picked, "roomArea"), text(picked, "roomWindow"),
                text(picked, "meal"), text(picked, "refund"));
        if (!room.isEmpty()) {
            rooms.add(price == null ? room : room + " ￥" + price);
        }
        String score = picked.path("commentScore").isNumber() ? "途牛评分 " + picked.path("commentScore").asText() : null;
        return Quote.ok("途牛", price, "每晚最低价起，以预订页为准；途牛没给预订链接，去途牛 App 搜酒店名",
                null, picked.path("hotelName").asText(), rooms, score);
    }

    /** 去掉括号、空格和标点，方便比较酒店名。 */
    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("[\\s()（）·・\\-]", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null) {
                out.add(p);
            }
        }
        return String.join(sep, out);
    }
}
