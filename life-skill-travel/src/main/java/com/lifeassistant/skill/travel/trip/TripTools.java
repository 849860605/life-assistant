package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.auth.ToolContexts;
import com.lifeassistant.core.card.CardHub;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给模型用的行程工具：弹需求卡、保存行程草稿（含改版）、确认、列出、读取行程，用户身份从 ToolContext 取。
 * 保存和需求卡都会发卡片给前端。
 */
@Component
@RequiredArgsConstructor
public class TripTools {

    /** 需求卡上"旅行偏好"的可选项 */
    static final List<String> INTERESTS = List.of("自然风光", "历史文化", "美食体验", "亲子友好", "轻松休闲", "城市漫步",
            "购物", "拍照打卡", "户外徒步");

    private final TripService tripService;
    private final CardHub cards;
    private final ObjectMapper objectMapper;

    /** 模型填的一项安排。 */
    public record PlanItem(
            @ToolParam(description = "开始时间 HH:mm") String start,
            @ToolParam(description = "结束时间 HH:mm", required = false) String end,
            @ToolParam(description = "类型：SPOT 景点 / FOOD 吃饭 / HOTEL 住宿 / TRANSPORT 交通 / FREE 自由活动") String type,
            @ToolParam(description = "地点或活动名，例如：成都大熊猫繁育研究基地") String name,
            @ToolParam(description = "地址或所在区域", required = false) String address,
            @ToolParam(description = "一句说明：玩什么、怎么去、注意什么", required = false) String note,
            @ToolParam(description = "人均花费（元），不知道不填", required = false) Integer cost,
            @ToolParam(description = "餐厅填 findRestaurants 返回的 dianpingUrl，酒店填预订链接；没有不填", required = false) String url) {
    }

    /** 模型填的一天。 */
    public record PlanDay(
            @ToolParam(description = "日期 yyyy-MM-dd") String date,
            @ToolParam(description = "当天主题，例如：熊猫基地 + 市区漫步", required = false) String theme,
            @ToolParam(description = "当天的安排，按时间先后") List<PlanItem> items) {
    }

    /** 发需求卡，把已知的信息预先填上，等用户确认或补充后再规划。 */
    @Tool(description = """
            给用户弹一张需求卡（目的地、日期、出发地、同行人、预算、偏好、去过的地方、节奏），用户在卡片上确认或修改后提交。
            完整规划行程前、关键信息不全时调用，已知的信息都预先填上。调用后这一轮就结束，等用户提交。""")
    public Map<String, Object> askTripRequirements(
            @ToolParam(description = "目的地", required = false) String destination,
            @ToolParam(description = "出发城市，没说就用记忆里的常住城市", required = false) String origin,
            @ToolParam(description = "开始日期 yyyy-MM-dd", required = false) String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd", required = false) String endDate,
            @ToolParam(description = "成人数", required = false) Integer adults,
            @ToolParam(description = "小孩数", required = false) Integer children,
            @ToolParam(description = "老人数", required = false) Integer elders,
            @ToolParam(description = "每人预算（元）", required = false) Integer budgetPerPerson,
            @ToolParam(description = "已知的旅行偏好，例如：自然风光、美食体验", required = false) List<String> interests,
            @ToolParam(description = "已经去过、不想再去的地方", required = false) List<String> visited,
            @ToolParam(description = "节奏：轻松 / 适中 / 紧凑", required = false) String pace) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("destination", destination);
        data.put("origin", origin);
        data.put("startDate", startDate);
        data.put("endDate", endDate);
        data.put("adults", adults);
        data.put("children", children);
        data.put("elders", elders);
        data.put("budgetPerPerson", budgetPerPerson);
        data.put("interests", interests == null ? List.of() : interests);
        data.put("visited", visited == null ? List.of() : visited);
        data.put("pace", pace);
        data.put("interestOptions", INTERESTS);
        if (!cards.emit("requirement", data, "需求卡：" + (destination == null ? "目的地待填" : destination) + "，等用户确认")) {
            return Map.of("error", "当前界面不能显示需求卡，改成用文字一次问清缺的信息");
        }
        return Map.of("card", "需求卡已展示给用户。这一轮到此为止：用一句话请用户确认或补充需求，不要追问，不要开始规划。");
    }

    /**
     * 保存行程：校验逐日安排，生成正文，存一个新版本，发行程卡。新行程是草稿，用户认可后用 confirmTrip 确认。
     */
    @Tool(description = """
            保存行程并以行程卡展示给用户（新行程是草稿）。规划好后直接调用，不要先在回答里把逐日行程写一遍；
            修改已保存的行程时传 tripId，会生成新版本。days 写完整的逐日安排；注意事项、装备清单、天气提醒写进 tips（Markdown）。
            返回 error 时按 problems 改好再调用一次。""")
    public Map<String, Object> saveTripPlan(
            @ToolParam(description = "已有行程的 id；新行程不填", required = false) Long tripId,
            @ToolParam(description = "标题，例如：成都4天3晚亲子游") String title,
            @ToolParam(description = "类型：CITY 城市游 / HIKING 爬山徒步 / MIXED 混合") String tripType,
            @ToolParam(description = "目的地") String destination,
            @ToolParam(description = "开始日期 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd") String endDate,
            @ToolParam(description = "逐日安排") List<PlanDay> days,
            @ToolParam(description = "注意事项、装备清单、天气提醒、预算估算，Markdown", required = false) String tips,
            @ToolParam(description = "这次改了什么，例如：第二天下雨改成室内", required = false) String changeNote,
            ToolContext toolContext) {
        LocalDate start;
        LocalDate end;
        try {
            start = date(startDate);
            end = date(endDate);
        } catch (DateTimeParseException e) {
            return Map.of("error", "日期要写成 yyyy-MM-dd");
        }
        Itinerary itinerary = toItinerary(days, tips);
        List<String> problems = itinerary.problems(start, end);
        if (!problems.isEmpty()) {
            return Map.of("error", "行程有问题，没有保存", "problems", problems);
        }
        TripService.TripDetail detail = tripService.saveVersion(ToolContexts.user(toolContext), tripId,
                new TripService.TripDraft(title, tripType, destination, start, end, itinerary.toMarkdown(),
                        changeNote == null ? (tripId == null ? "首次生成" : "修改") : changeNote, json(itinerary), true),
                "AI");
        Trip trip = detail.trip();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tripId", trip.getId());
        out.put("version", detail.version().getVersion());
        out.put("status", trip.getStatus());
        if (cards.emit("trip", tripCard(trip, detail.version().getVersion(), itinerary), summary(trip, itinerary))) {
            out.put("card", "行程已作为卡片展示给用户，用户点开能看逐日详情。回答里不要再写逐日行程，只用两三句说整体思路、亮点和要注意的，"
                    + "最后问用户是否确认保存或要调整哪里");
        }
        if ("DRAFT".equals(trip.getStatus())) {
            out.put("hint", "用户认可后调用 confirmTrip；相关提醒用 createEvent 建，并带上这个 tripId");
        }
        return out;
    }

    /** 用户认可行程后确认，草稿变成已确认。 */
    @Tool(description = "用户认可行程（\"可以\"\"就这个\"\"保存\"）时调用，把草稿确认下来。")
    public Map<String, Object> confirmTrip(@ToolParam(description = "行程 id") long tripId, ToolContext toolContext) {
        Trip trip = tripService.confirm(ToolContexts.user(toolContext), tripId);
        return Map.of("tripId", trip.getId(), "title", trip.getTitle(), "status", trip.getStatus(),
                "hint", "相关提醒用 createEvent 建，并带上这个 tripId");
    }

    /** 列出当前用户的行程摘要（不含正文），空字段转成空串。 */
    @Tool(description = "列出用户保存过的行程。")
    public List<Map<String, Object>> listMyTrips(ToolContext toolContext) {
        return tripService.list(ToolContexts.user(toolContext)).stream()
                .map(t -> Map.<String, Object>of(
                        "tripId", t.getId(),
                        "title", t.getTitle(),
                        "destination", t.getDestination() == null ? "" : t.getDestination(),
                        "startDate", t.getStartDate() == null ? "" : t.getStartDate().toString(),
                        "status", t.getStatus(),
                        "version", t.getCurrentVer()))
                .toList();
    }

    /** 读取行程当前版本：有结构化行程时返回 itinerary，早期的行程只有 Markdown 正文。 */
    @Tool(description = "读取一个已保存行程的当前内容，修改行程前先读。")
    public Map<String, Object> getTrip(@ToolParam(description = "行程 id") long tripId, ToolContext toolContext) {
        TripService.TripDetail detail = tripService.get(ToolContexts.user(toolContext), tripId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tripId", tripId);
        out.put("title", detail.trip().getTitle());
        out.put("startDate", String.valueOf(detail.trip().getStartDate()));
        out.put("endDate", String.valueOf(detail.trip().getEndDate()));
        out.put("version", detail.version().getVersion());
        if (detail.version().getItinerary() != null) {
            out.put("itinerary", parse(detail.version().getItinerary()));
        } else {
            out.put("content", detail.version().getContent());
        }
        return out;
    }

    /** 模型填的逐日安排转成存储结构，给每项编号 d{第几天}-{第几项}。 */
    static Itinerary toItinerary(List<PlanDay> days, String tips) {
        List<Itinerary.Day> out = new ArrayList<>();
        if (days != null) {
            for (int d = 0; d < days.size(); d++) {
                PlanDay day = days.get(d);
                List<Itinerary.Item> items = new ArrayList<>();
                List<PlanItem> planItems = day.items() == null ? List.of() : day.items();
                for (int i = 0; i < planItems.size(); i++) {
                    PlanItem p = planItems.get(i);
                    String type = p.type() == null ? "SPOT" : p.type().trim().toUpperCase();
                    items.add(new Itinerary.Item("d" + (d + 1) + "-" + (i + 1), blankToNull(p.start()),
                            blankToNull(p.end()), Itinerary.TYPES.contains(type) ? type : "SPOT", p.name(),
                            blankToNull(p.address()), blankToNull(p.note()), p.cost(), blankToNull(p.url()), null));
                }
                out.add(new Itinerary.Day(day.date(), blankToNull(day.theme()), items));
            }
        }
        return new Itinerary(out, blankToNull(tips));
    }

    /** 行程卡的数据：基本信息加每天的主题和安排（时间、类型、名称）。 */
    private static Map<String, Object> tripCard(Trip trip, int version, Itinerary itinerary) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("tripId", trip.getId());
        card.put("version", version);
        card.put("title", trip.getTitle());
        card.put("destination", trip.getDestination());
        card.put("startDate", String.valueOf(trip.getStartDate()));
        card.put("endDate", String.valueOf(trip.getEndDate()));
        card.put("status", trip.getStatus());
        card.put("days", itinerary.days());
        return card;
    }

    /** 行程卡的文字版：标题、id、每天的地点。 */
    private static String summary(Trip trip, Itinerary itinerary) {
        StringBuilder out = new StringBuilder("行程卡：《" + trip.getTitle() + "》tripId=" + trip.getId() + "（"
                + ("DRAFT".equals(trip.getStatus()) ? "草稿" : "已确认") + "）");
        for (int i = 0; i < itinerary.days().size(); i++) {
            out.append("；D").append(i + 1).append(" ")
                    .append(String.join("、", itinerary.days().get(i).items().stream().map(Itinerary.Item::name).toList()));
        }
        return out.toString();
    }

    private String json(Itinerary itinerary) {
        try {
            return objectMapper.writeValueAsString(itinerary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object parse(String json) {
        try {
            return objectMapper.readValue(json, Itinerary.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 解析 yyyy-MM-dd，空串当作未填。 */
    private static LocalDate date(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
    }
}
