package com.lifeassistant.skill.travel.flight;

import java.util.List;
import java.util.Map;

/**
 * 出发城市附近还能去坐飞机的城市：比如深圳出发，香港、广州的航线更多、有时更便宜。
 * 每个备选写清从出发城市过去要多久、每人大概多少钱、要不要港澳通行证，比价时把这部分算进去。
 * 数字是常见走法的粗估（口岸巴士、高铁、城际），给用户参考，不是实时报价。
 */
public final class NearbyAirports {

    private NearbyAirports() {
    }

    /** 一个备选出发城市：到那边机场要多少分钟、每人多少钱、怎么去、要不要港澳通行证。 */
    public record Alt(String city, int minutes, int cost, String how, boolean permit) {

        /** 给用户看的一句话，如"深圳到香港机场约 1.5 小时、每人约 ¥150（口岸巴士），要带港澳通行证" */
        public String describe(String from) {
            String time = minutes % 60 == 0 ? minutes / 60 + " 小时" : minutes >= 60 ? String.format("%.1f 小时", minutes / 60.0) : minutes + " 分钟";
            return from + "到" + city + "机场约 " + time + "、每人约 ¥" + cost + "（" + how + "）" + (permit ? "，要带港澳通行证" : "");
        }
    }

    private static final Map<String, List<Alt>> TABLE = Map.ofEntries(
            Map.entry("深圳", List.of(new Alt("香港", 90, 150, "口岸巴士或港铁转机场快线", true),
                    new Alt("广州", 90, 100, "高铁到广州再坐地铁去白云机场", false))),
            Map.entry("东莞", List.of(new Alt("深圳", 60, 60, "城际或打车", false),
                    new Alt("广州", 60, 60, "城际或打车", false),
                    new Alt("香港", 150, 200, "跨境巴士", true))),
            Map.entry("广州", List.of(new Alt("深圳", 90, 100, "高铁到深圳再坐地铁去宝安机场", false),
                    new Alt("香港", 150, 250, "高铁到西九龙再坐机场快线", true))),
            Map.entry("佛山", List.of(new Alt("广州", 60, 50, "地铁或打车", false))),
            Map.entry("珠海", List.of(new Alt("澳门", 40, 50, "拱北口岸过关", true),
                    new Alt("香港", 90, 160, "港珠澳大桥穿梭巴士", true),
                    new Alt("广州", 90, 100, "城际到广州再去白云机场", false))),
            Map.entry("中山", List.of(new Alt("广州", 80, 80, "城际或打车", false),
                    new Alt("珠海", 60, 60, "城际或打车", false))),
            Map.entry("苏州", List.of(new Alt("上海", 60, 60, "高铁到虹桥", false))),
            Map.entry("无锡", List.of(new Alt("上海", 70, 80, "高铁到虹桥", false))),
            Map.entry("杭州", List.of(new Alt("上海", 80, 90, "高铁到虹桥", false))),
            Map.entry("天津", List.of(new Alt("北京", 60, 60, "城际到北京南再去机场", false))),
            Map.entry("廊坊", List.of(new Alt("北京", 50, 50, "去大兴机场", false))));

    /** 出发城市的备选，没有返回空列表。城市名里带"市"也认。 */
    public static List<Alt> of(String origin) {
        if (origin == null) {
            return List.of();
        }
        String city = origin.trim().replaceAll("市$", "");
        return TABLE.getOrDefault(city, List.of());
    }
}
