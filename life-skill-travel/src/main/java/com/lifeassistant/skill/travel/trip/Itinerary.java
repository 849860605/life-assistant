package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 结构化行程，存在 t_trip_version.itinerary：逐日的安排加一段注意事项。行程正文（Markdown）由它生成。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Itinerary(List<Day> days, String tips) {

    /** 安排的类型：景点、吃饭、住宿、交通、自由活动 */
    public static final Set<String> TYPES = Set.of("SPOT", "FOOD", "HOTEL", "TRANSPORT", "FREE");

    /** 一天：日期 yyyy-MM-dd、当天主题、按时间排的安排。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(String date, String theme, List<Item> items) {
    }

    /**
     * 一项安排。id 形如 d1-2（第 1 天第 2 项），以后单项替换、锁定用它定位；locked 表示用户要保留，重新规划时不动。
     * start、end 是 HH:mm；cost 是人均花费（元）；url 是点评或预订链接。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String start, String end, String type, String name, String address, String note,
                       Integer cost, String url, Boolean locked) {
    }

    /**
     * 检查明显的错误：没有安排、日期格式不对或超出行程日期、时间格式不对、结束早于开始、同一天时间重叠。
     * 返回问题列表，空表示没问题。
     */
    public List<String> problems(LocalDate startDate, LocalDate endDate) {
        List<String> problems = new ArrayList<>();
        if (days == null || days.isEmpty()) {
            problems.add("days 为空，至少要有一天的安排");
            return problems;
        }
        for (Day day : days) {
            String label = day.date() == null ? "某一天" : day.date();
            LocalDate date = parseDate(day.date());
            if (date == null) {
                problems.add(label + "：日期要写成 yyyy-MM-dd");
            } else if ((startDate != null && date.isBefore(startDate)) || (endDate != null && date.isAfter(endDate))) {
                problems.add(label + "：不在行程日期 " + startDate + " 到 " + endDate + " 之内");
            }
            if (day.items() == null || day.items().isEmpty()) {
                problems.add(label + "：没有安排");
                continue;
            }
            LocalTime lastEnd = null;
            String lastName = null;
            for (Item item : day.items()) {
                if (item.name() == null || item.name().isBlank()) {
                    problems.add(label + "：有一项没写地点名");
                }
                LocalTime start = parseTime(item.start());
                LocalTime end = parseTime(item.end());
                if ((item.start() != null && start == null) || (item.end() != null && end == null)) {
                    problems.add(label + " " + item.name() + "：时间要写成 HH:mm");
                    continue;
                }
                if (start != null && end != null && end.isBefore(start)) {
                    problems.add(label + " " + item.name() + "：结束时间早于开始时间");
                }
                if (start != null && lastEnd != null && start.isBefore(lastEnd)) {
                    problems.add(label + "：" + lastName + " 和 " + item.name() + " 时间重叠");
                }
                if (end != null) {
                    lastEnd = end;
                    lastName = item.name();
                }
            }
        }
        return problems;
    }

    /** 生成行程正文：每天一个二级标题，下面每项一行"时间 地点（地址）— 说明 · 人均"，最后接注意事项。 */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            out.append("## D").append(i + 1).append(" ").append(dayLabel(day.date()));
            if (day.theme() != null && !day.theme().isBlank()) {
                out.append(" · ").append(day.theme());
            }
            out.append("\n\n");
            for (Item item : day.items()) {
                out.append("- ");
                if (item.start() != null) {
                    out.append(item.start()).append(item.end() == null ? "" : "–" + item.end()).append(" ");
                }
                out.append(item.url() == null ? "**" + item.name() + "**" : "[**" + item.name() + "**](" + item.url() + ")");
                if (item.address() != null && !item.address().isBlank()) {
                    out.append("（").append(item.address()).append("）");
                }
                if (item.note() != null && !item.note().isBlank()) {
                    out.append(" — ").append(item.note());
                }
                if (item.cost() != null && item.cost() > 0) {
                    out.append(" · 人均 ¥").append(item.cost());
                }
                out.append("\n");
            }
            out.append("\n");
        }
        if (tips != null && !tips.isBlank()) {
            out.append("## 注意事项\n\n").append(tips.strip()).append("\n");
        }
        return out.toString();
    }

    /** "2026-03-10" 显示成"3月10日 周二"，解析不了原样返回。 */
    private static String dayLabel(String date) {
        LocalDate d = parseDate(date);
        return d == null ? String.valueOf(date)
                : d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA);
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String text) {
        try {
            return text == null || text.isBlank() ? null : LocalTime.parse(text.trim().length() == 4 ? "0" + text.trim() : text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
