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
 * 结构化行程，存在 t_trip_version.itinerary：逐日的安排、住宿和一段注意事项。行程正文（Markdown）由它生成。
 * geo 为 true 表示已经用高德补过坐标、照片和路程（补不上的项也算补过，不再重试）。
 * booked 是用户标记"已预约"的安排和住宿的 id（如 d1-3、s1）：用户自己在平台上订好后在页面上标记，不经过我们下单。
 * bookingStatus 记"已跳转平台"（值 OPENED）：用户点过预订链接，但还没确认订好，不能当成预订成功；
 * 已确认的只看 booked，两边不会对不上。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Itinerary(List<Day> days, String tips, Boolean geo, List<Stay> stays, List<String> booked,
                        java.util.Map<String, String> bookingStatus) {

    /** 预订状态：计划预订、已跳转平台、已确认订单 */
    public static final String PLANNED = "PLANNED";
    public static final String OPENED = "OPENED";
    public static final String CONFIRMED = "CONFIRMED";

    public Itinerary(List<Day> days, String tips, Boolean geo, List<Stay> stays, List<String> booked) {
        this(days, tips, geo, stays, booked, null);
    }

    public Itinerary(List<Day> days, String tips) {
        this(days, tips, null, null, null, null);
    }

    /** 换掉逐日安排，其余不变。 */
    public Itinerary withDays(List<Day> newDays) {
        return new Itinerary(newDays, tips, geo, stays, booked, bookingStatus);
    }

    /** 换掉住宿，其余不变。 */
    public Itinerary withStays(List<Stay> newStays) {
        return new Itinerary(days, tips, geo, newStays, booked, bookingStatus);
    }

    /** 换掉已预约的 id 列表，其余不变；空列表存成 null。 */
    public Itinerary withBooked(List<String> newBooked) {
        return new Itinerary(days, tips, geo, stays, newBooked == null || newBooked.isEmpty() ? null : List.copyOf(newBooked),
                bookingStatus);
    }

    /** 换掉"已跳转平台"的记录，其余不变；空的存成 null。 */
    public Itinerary withBookingStatus(java.util.Map<String, String> status) {
        return new Itinerary(days, tips, geo, stays, booked,
                status == null || status.isEmpty() ? null : java.util.Map.copyOf(status));
    }

    /** 标记已补过地理信息。 */
    public Itinerary located(List<Day> newDays, List<Stay> newStays) {
        return new Itinerary(newDays, tips, true, newStays, booked, bookingStatus);
    }

    /** 某项的预订状态：在 booked 里是已确认，点过预订链接是已跳转平台，否则是计划预订。 */
    public String bookingOf(String id) {
        if (isBooked(id)) {
            return CONFIRMED;
        }
        return bookingStatus != null && OPENED.equals(bookingStatus.get(id)) ? OPENED : PLANNED;
    }

    /** 这一项（安排或住宿的 id）是不是已预约。 */
    public boolean isBooked(String id) {
        return booked != null && booked.contains(id);
    }

    /**
     * 一段住宿：checkIn 当晚入住、checkOut 当天离店（yyyy-MM-dd）。price 是每晚最低价，platform、url 是最低价所在平台
     * 和预订链接，quotes 是各平台报价，queriedAt 是比价时间；options 是比价时的其他候选，换酒店时从里面选。
     * poiId、lng、lat、photo、rating 由后端在高德补上。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stay(String id, String name, String checkIn, String checkOut, String address, Integer price,
                       String platform, String url, String queriedAt, List<StayQuote> quotes,
                       List<StayOption> options, String poiId, Double lng, Double lat, String photo,
                       Double rating) {

        /** 住几晚，日期不对返回 0。 */
        public int nights() {
            LocalDate in = parseDate(checkIn);
            LocalDate out = parseDate(checkOut);
            return in == null || out == null || !out.isAfter(in) ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(in, out);
        }

        /** 这一天和这段住宿有关：入住当天到离店当天（含）。 */
        public boolean covers(String date) {
            LocalDate d = parseDate(date);
            LocalDate in = parseDate(checkIn);
            LocalDate out = parseDate(checkOut);
            return d != null && in != null && out != null && !d.isBefore(in) && !d.isAfter(out);
        }

        public boolean located() {
            return lng != null && lat != null;
        }
    }

    /** 一个平台的报价：每晚价格、预订链接、价格口径、平台上匹配到的酒店名。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StayQuote(String platform, Integer price, String url, String basis, String matchedName) {
    }

    /** 比价时的另一家候选酒店。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StayOption(String name, Integer price, String platform, String url, List<StayQuote> quotes) {
    }

    /** 安排的类型：景点、吃饭、住宿、交通、自由活动 */
    public static final Set<String> TYPES = Set.of("SPOT", "FOOD", "HOTEL", "TRANSPORT", "FREE");

    /** 一天：日期 yyyy-MM-dd、当天主题、按时间排的安排、相邻两项之间的路程。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(String date, String theme, List<Item> items, List<Leg> legs) {

        public Day(String date, String theme, List<Item> items) {
            this(date, theme, items, null);
        }
    }

    /**
     * 一项安排。id 形如 d1-2（第 1 天第 2 项），单项替换、锁定用它定位；locked 表示用户要保留，重新规划时不动。
     * start、end 是 HH:mm；cost 是人均花费（元）；url 是点评或预订链接。
     * poiId、lng、lat、photo、rating 由后端按名称在高德补上，模型不填。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String start, String end, String type, String name, String address, String note,
                       Integer cost, String url, Boolean locked, String poiId, Double lng, Double lat, String photo,
                       Double rating) {

        public Item(String id, String start, String end, String type, String name, String address, String note,
                    Integer cost, String url, Boolean locked) {
            this(id, start, end, type, name, address, note, cost, url, locked, null, null, null, null, null);
        }

        public boolean located() {
            return lng != null && lat != null;
        }
    }

    /** 相邻两项之间怎么走：mode 是 WALK 步行或 DRIVE 打车/自驾，minutes 分钟，km 公里，taxi 是高德估的打车费（元）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Leg(String from, String to, String mode, int minutes, double km, Integer taxi) {

        public Leg(String from, String to, String mode, int minutes, double km) {
            this(from, to, mode, minutes, km, null);
        }
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
                if (isBooked(item.id())) {
                    out.append(" · 已预约");
                }
                out.append("\n");
            }
            out.append("\n");
        }
        if (stays != null && !stays.isEmpty()) {
            out.append("## 住宿\n\n");
            for (Stay stay : stays) {
                out.append("- ").append(dayLabel(stay.checkIn())).append(" – ").append(dayLabel(stay.checkOut()))
                        .append("（").append(stay.nights()).append(" 晚）")
                        .append(stay.url() == null ? "**" + stay.name() + "**" : "[**" + stay.name() + "**](" + stay.url() + ")");
                if (stay.price() != null) {
                    out.append(" · 约 ¥").append(stay.price()).append("/晚（").append(stay.platform()).append("，以预订页为准）");
                }
                if (isBooked(stay.id())) {
                    out.append(" · 已预约");
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
