package com.lifeassistant.skill.travel.train;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.conversation.TurnFlags;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 火车票（高铁、动车、普速）：只查飞猪一家，返回几班车次（车次号、起止站、时间、时长、最低座席价格、余票），
 * 挑一班写进行程，票价和购票链接保存时自动带上。星络不会替用户买票。
 */
@Component
@RequiredArgsConstructor
public class TrainTools {

    /** 返回给模型的车次数 */
    private static final int QUICK_TRAINS = 5;
    /** 车次号：G/D/C/Z/T/K/Y/S 开头加 2～4 位数字（一位数字的多半是航站楼，如"宝安T3"） */
    public static final Pattern TRAIN_IN_NAME = Pattern.compile("(?<![A-Z0-9])[GDCZTKYS]\\d{2,4}(?!\\d)");

    private final FliggyCli fliggy;
    private final TrainBook trainBook;
    private final TurnFlags turnFlags;

    /**
     * 一个车次：transfer 为 true 是中转（via 是中转站），minutes 是全程分钟，price 是最低座席的价格，
     * seat 是那个座席，left 是余票（查不到为空），url 是飞猪的购票页。
     */
    public record Train(String trainNo, String depTime, String arrTime, String depStation, String arrStation,
                        Integer minutes, Double price, String seat, Integer left, String url, boolean transfer,
                        String via) {
    }

    @Tool(description = """
            查高铁、动车、火车票：只查飞猪一家，几秒出结果，返回几班车次（车次号、出发到达站、时间、时长、价格、余票）。
            大交通选了高铁/火车，或者没选但两地适合坐高铁（大约 60～800 公里、有高铁线，深圳广州去香港也可以到西九龙）时用。
            从里面挑一班写进行程（TRANSPORT 项的 name 写"高铁 G1234 深圳北 → 长沙南"这样，带车次号），
            票价和购票链接后端会自动带上。往返就去程、回程各调一次，可以和酒店、天气一起并行调用。""")
    public Map<String, Object> quickTrains(
            @ToolParam(description = "出发城市或车站，例如：深圳、深圳北") String origin,
            @ToolParam(description = "到达城市或车站，例如：长沙、香港西九龙") String destination,
            @ToolParam(description = "出发日期 yyyy-MM-dd") String date,
            @ToolParam(description = "偏好：直达 / 早班 / 下午 / 晚班 / 最快 / 便宜，没有就不填", required = false) String preference) {
        turnFlags.mark(TurnFlags.PLANNING);
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            return Map.of("error", "日期格式不对，要 yyyy-MM-dd");
        }
        if (day.isBefore(LocalDate.now())) {
            return Map.of("error", day + " 已经过去了");
        }
        if (!fliggy.available()) {
            return Map.of("error", "飞猪没有配置，查不了火车票；按常识写车次时段，提醒用户去 12306 或飞猪买票");
        }
        JsonNode result;
        try {
            result = fliggy.run(List.of("search-train", "--origin", origin.trim(), "--destination", destination.trim(),
                    "--dep-date", day.toString(), "--sort-type", "6"));
        } catch (RuntimeException e) {
            return Map.of("error", "飞猪查火车票失败：" + e.getMessage());
        }
        List<Train> trains = pick(parse(result), preference);
        if (trains.isEmpty()) {
            return Map.of("error", "飞猪没查到这天的车次（可能还没开售或没有直达），可以换个日期，或者改坐飞机、自驾");
        }
        CallRecorder.Scope scope = CallRecorder.current();
        trainBook.record(scope == null ? null : scope.conversationId(), day.toString(), trains);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("trains", trains.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trainNo", t.trainNo());
            m.put("time", t.depTime() + "-" + t.arrTime());
            m.put("stations", t.depStation() + " → " + t.arrStation());
            m.put("minutes", t.minutes());
            m.put("price", t.price() == null ? "暂无" : t.price() + "（" + t.seat() + "）");
            if (t.left() != null) {
                m.put("left", t.left() == 0 ? "无票" : t.left() + " 张");
            }
            if (t.transfer()) {
                m.put("transfer", t.via() == null ? "中转" : "在" + t.via() + "中转");
            }
            return m;
        }).toList());
        out.put("note", "价格只来自飞猪，是参考价，以购票页为准；星络不会替用户买票");
        return out;
    }

    /** 飞猪返回的 itemList 转成车次，第一段的车次号作为这班的车次号（中转时记中转站）。 */
    static List<Train> parse(JsonNode result) {
        List<Train> out = new ArrayList<>();
        for (JsonNode item : result.path("data").path("itemList")) {
            JsonNode journey = item.path("journeys").path(0);
            JsonNode segs = journey.path("segments");
            if (!segs.isArray() || segs.isEmpty()) {
                continue;
            }
            JsonNode first = segs.get(0);
            JsonNode last = segs.get(segs.size() - 1);
            String no = first.path("marketingTransportNo").asText("");
            if (no.isBlank()) {
                continue;
            }
            boolean transfer = segs.size() > 1;
            Double price = null;
            try {
                price = item.path("price").isMissingNode() || item.path("price").asText("").isBlank() ? null
                        : Double.valueOf(item.path("price").asText());
            } catch (NumberFormatException ignored) {
                // 没价格就留空
            }
            Integer left = null;
            if (first.path("quantity").isNumber()) {
                left = first.path("quantity").asInt();
            } else if (first.path("quantity").asText("").matches("\\d+")) {
                left = Integer.valueOf(first.path("quantity").asText());
            }
            Integer minutes = item.path("totalDuration").asText("").matches("\\d+") ? Integer.valueOf(item.path("totalDuration").asText()) : null;
            out.add(new Train(transfer ? no + "/" + last.path("marketingTransportNo").asText("") : no,
                    hm(first.path("depDateTime").asText("")), hm(last.path("arrDateTime").asText("")),
                    first.path("depStationName").asText(""), last.path("arrStationName").asText(""), minutes, price,
                    first.path("seatClassName").asText(null), left, item.path("jumpUrl").asText(null), transfer,
                    transfer ? first.path("arrStationName").asText(null) : null));
        }
        return out;
    }

    /** 按偏好挑几班：有直达时去掉中转；早班、下午、晚班按出发时间筛；最快按时长、便宜按价格，其余按出发时间。 */
    static List<Train> pick(List<Train> trains, String preference) {
        String p = preference == null ? "" : preference;
        List<Train> list = new ArrayList<>(trains);
        if (list.stream().anyMatch(t -> !t.transfer())) {
            list.removeIf(Train::transfer);
        }
        Predicate<Train> slot = p.contains("早") ? t -> t.depTime().compareTo("11:00") < 0
                : p.contains("下午") ? t -> t.depTime().compareTo("12:00") >= 0 && t.depTime().compareTo("18:00") < 0
                : p.contains("晚") ? t -> t.depTime().compareTo("18:00") >= 0 : null;
        if (slot != null && list.stream().anyMatch(slot)) {
            list.removeIf(slot.negate());
        }
        list.removeIf(t -> t.left() != null && t.left() == 0 && list.stream().anyMatch(o -> o.left() == null || o.left() > 0));
        if (p.contains("快")) {
            list.sort(Comparator.comparing(Train::minutes, Comparator.nullsLast(Comparator.naturalOrder())));
        } else if (p.contains("便宜")) {
            list.sort(Comparator.comparing(Train::price, Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return list.size() > QUICK_TRAINS ? new ArrayList<>(list.subList(0, QUICK_TRAINS)) : list;
    }

    /** "2026-11-20 06:33:00" 取 "06:33" */
    private static String hm(String dateTime) {
        return dateTime.length() >= 16 ? dateTime.substring(11, 16) : dateTime;
    }
}
