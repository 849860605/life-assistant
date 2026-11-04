package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.auth.ToolContexts;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.hotel.HotelPriceSource;
import com.lifeassistant.skill.travel.hotel.HotelQuoteBook;
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
import java.util.Set;

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

    /** 方案名漏填时按顺序用这几个 */
    static final List<String> DEFAULT_PLAN_TITLES = List.of("省钱经典", "舒适品质", "深度体验");

    private final TripService tripService;
    private final TripEditor tripEditor;
    private final HotelQuoteBook quoteBook;
    private final CardHub cards;
    private final ObjectMapper objectMapper;
    private final TripPriceCheck priceCheck;

    /** 模型填的一项安排。 */
    public record PlanItem(
            @ToolParam(description = "开始时间 HH:mm") String start,
            @ToolParam(description = "结束时间 HH:mm", required = false) String end,
            @ToolParam(description = "类型：SPOT 景点 / FOOD 吃饭 / HOTEL 住宿 / TRANSPORT 交通 / FREE 自由活动") String type,
            @ToolParam(description = "一个地点的正式名称，例如：成都大熊猫繁育研究基地。一项只写一个地点，"
                    + "顺路的几个地方（如断桥、白堤、平湖秋月）拆成几项；不要写成\"A → B\"或\"A + 活动\"") String name,
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

    /** 模型填的一段住宿。 */
    public record PlanStay(
            @ToolParam(description = "酒店完整名称，用 quickHotels 返回的 name 或 findAndCompareHotels / compareHotelPrices 返回的 hotel 原样填") String hotel,
            @ToolParam(description = "入住日期 yyyy-MM-dd") String checkIn,
            @ToolParam(description = "离店日期 yyyy-MM-dd") String checkOut) {
    }

    /** 方案里的一天概要。 */
    public record OutlineDay(
            @ToolParam(description = "第几天，从 1 开始") int day,
            @ToolParam(description = "当天主题，例如：古城墙 + 回民街") String title,
            @ToolParam(description = "当天主要去的地方，2-4 个真实地名") List<String> places) {
    }

    /** 三套方案中的一套。 */
    public record PlanOption(
            @ToolParam(description = "方案名（必填，四个字左右），例如：省钱经典、舒适品质、深度体验") String title,
            @ToolParam(description = "一句话风格，例如：经典景点全覆盖，地铁 + 经济型酒店") String style,
            @ToolParam(description = "每人预算估算（元），含大交通、住宿、门票、餐饮") Integer budgetPerPerson,
            @ToolParam(description = "3-4 条亮点，每条十来个字") List<String> highlights,
            @ToolParam(description = "住宿档次或区域，例如：钟楼附近四星酒店", required = false) String hotel,
            @ToolParam(description = "大交通怎么走，例如：深圳直飞赤峰；经北京中转；沈阳进、赤峰出；高铁往返", required = false) String transport,
            @ToolParam(description = "逐日概要") List<OutlineDay> outline) {
    }

    /** 给出三套不同风格的方案概要，用户选一套后再展开成完整行程。 */
    @Tool(description = """
            需求确认后、完整规划前调用：给出三套风格不同的方案概要（通常是省钱、舒适、深度体验，预算从低到高），
            以方案卡展示给用户比较。只写概要，不查机票酒店，不调用 saveTripPlan。调用后这一轮就结束，等用户选。""")
    public Map<String, Object> proposeTripPlans(
            @ToolParam(description = "目的地") String destination,
            @ToolParam(description = "开始日期 yyyy-MM-dd", required = false) String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd", required = false) String endDate,
            @ToolParam(description = "三套方案，预算从低到高") List<PlanOption> options) {
        if (options == null || options.isEmpty()) {
            return Map.of("error", "options 为空，要给出三套方案");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("destination", destination);
        data.put("startDate", startDate);
        data.put("endDate", endDate);
        data.put("days", options.stream().mapToInt(o -> o.outline() == null ? 0 : o.outline().size()).max().orElse(0));
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            PlanOption o = options.get(i);
            // 模型偶尔漏填方案名，按预算档位补一个
            String title = o.title() == null || o.title().isBlank()
                    ? DEFAULT_PLAN_TITLES.get(Math.min(i, DEFAULT_PLAN_TITLES.size() - 1)) : o.title();
            titles.add(title + (o.budgetPerPerson() == null ? "" : " 约" + o.budgetPerPerson() + "元/人"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", "p" + (i + 1));
            m.put("title", title);
            m.put("style", o.style());
            m.put("budgetPerPerson", o.budgetPerPerson());
            m.put("highlights", o.highlights() == null ? List.of() : o.highlights());
            m.put("hotel", o.hotel());
            m.put("transport", o.transport());
            m.put("outline", o.outline() == null ? List.of() : o.outline());
            list.add(m);
        }
        data.put("options", list);
        String summary = "方案卡：" + destination + "，" + String.join("；", titles);
        if (!cards.emit("plan_options", data, summary)) {
            return Map.of("error", "当前界面不能显示方案卡，改成用文字简要列出三套方案");
        }
        return Map.of("card", "三套方案已作为卡片展示给用户。这一轮到此为止：用一两句说三套的区别和你推荐哪套，请用户选，不要开始完整规划。");
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
            @ToolParam(description = "用户只说了玩几天、没说具体日期时填天数，日期留空", required = false) Integer days,
            @ToolParam(description = "成人数", required = false) Integer adults,
            @ToolParam(description = "小孩数", required = false) Integer children,
            @ToolParam(description = "老人数", required = false) Integer elders,
            @ToolParam(description = "每人预算（元）", required = false) Integer budgetPerPerson,
            @ToolParam(description = "已知的旅行偏好，例如：自然风光、美食体验", required = false) List<String> interests,
            @ToolParam(description = "已经去过、不想再去的地方", required = false) List<String> visited,
            @ToolParam(description = "节奏：轻松 / 适中 / 紧凑", required = false) String pace,
            @ToolParam(description = "大交通：尽量直飞 / 中转也行 / 只要直飞，用户说过才填", required = false) String transfer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("destination", destination);
        data.put("origin", origin);
        data.put("startDate", startDate);
        data.put("endDate", endDate);
        data.put("days", days);
        data.put("adults", adults);
        data.put("children", children);
        data.put("elders", elders);
        data.put("budgetPerPerson", budgetPerPerson);
        data.put("interests", interests == null ? List.of() : interests);
        data.put("visited", visited == null ? List.of() : visited);
        data.put("pace", pace);
        data.put("transfer", transfer);
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
            整份重新生成已保存的行程时传 tripId，会生成新版本；只改其中几项、某一天、加减天时不要用它，
            用 replaceTripItems、removeTripItems、addTripItems、reorderTripDay、swapTripDays、rewriteTripDays、changeTripHotel。
            days 写完整的逐日安排；过夜行程在 stays 里写住哪家酒店（用 quickHotels 或比价结果里的酒店名，
            价格和链接后端会自动带上）；坐飞机写成 TRANSPORT 项、name 带航班号（价格和链接按 quickFlights 结果自动带上）；注意事项、装备清单、天气提醒写进 tips（Markdown）。
            返回 error 时按 problems 改好再调用一次。""")
    public Map<String, Object> saveTripPlan(
            @ToolParam(description = "已有行程的 id；新行程不填", required = false) Long tripId,
            @ToolParam(description = "标题，例如：成都4天3晚亲子游") String title,
            @ToolParam(description = "类型：CITY 城市游 / HIKING 爬山徒步 / MIXED 混合") String tripType,
            @ToolParam(description = "目的地") String destination,
            @ToolParam(description = "开始日期 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd") String endDate,
            @ToolParam(description = "逐日安排；入住、回酒店休息、退房写成 HOTEL 类型") List<PlanDay> days,
            @ToolParam(description = "住宿，过夜行程必填，每段一家酒店；中途换酒店就分几段", required = false) List<PlanStay> stays,
            @ToolParam(description = "注意事项、装备清单、天气提醒、预算估算，Markdown", required = false) String tips,
            @ToolParam(description = "这次改了什么，例如：第二天下雨改成室内", required = false) String changeNote,
            @ToolParam(description = "市内主要怎么走：TRANSIT 地铁公交（默认）/ DRIVE 打车、包车或自驾；方案是包车、自驾或用户说要打车时填 DRIVE",
                    required = false) String transport,
            ToolContext toolContext) {
        LocalDate start;
        LocalDate end;
        try {
            start = date(startDate);
            end = date(endDate);
        } catch (DateTimeParseException e) {
            return Map.of("error", "日期要写成 yyyy-MM-dd");
        }
        Itinerary previous = tripId == null ? null : previous(toolContext, tripId);
        Itinerary itinerary = toItinerary(days, tips);
        if (previous != null) {
            itinerary = carryOver(itinerary, previous);
        }
        CallRecorder.Scope scope = CallRecorder.current();
        itinerary = itinerary.withStays(toStays(stays, scope == null ? null : scope.conversationId(), previous));
        itinerary = carryBooked(itinerary, previous);
        itinerary = carryDone(itinerary, previous);
        itinerary = carryTransport(itinerary, previous);
        if (transport != null && Set.of(Itinerary.TRANSIT, Itinerary.DRIVE).contains(transport.trim().toUpperCase())) {
            itinerary = itinerary.withTransport(transport.trim().toUpperCase(), itinerary.legModes());
        }
        // 航班按这个会话查过的填上价格和链接；只查过一个平台的酒店、航班，保存后在后台补比价
        TripPriceCheck.Prepared prepared = priceCheck.prepare(itinerary, scope == null ? null : scope.conversationId());
        itinerary = prepared.itinerary();
        List<String> problems = new ArrayList<>(itinerary.problems(start, end));
        for (Itinerary.Stay st : itinerary.stays()) {
            if (st.nights() == 0) {
                problems.add("住宿 " + st.name() + "：入住、离店日期要写成 yyyy-MM-dd，离店晚于入住");
            } else if ((start != null && st.checkIn().compareTo(start.toString()) < 0)
                    || (end != null && st.checkOut().compareTo(end.toString()) > 0)) {
                problems.add("住宿 " + st.name() + "：日期不在行程 " + start + " 到 " + end + " 之内");
            }
        }
        if (itinerary.days().size() > 1 && itinerary.stays().isEmpty()) {
            problems.add("过夜行程要填 stays（住哪家酒店、入住和离店日期），酒店名用比价结果里的");
        }
        if (!problems.isEmpty()) {
            return Map.of("error", "行程有问题，没有保存", "problems", problems);
        }
        TripService.TripDetail detail = tripService.saveVersion(ToolContexts.user(toolContext), tripId,
                new TripService.TripDraft(title, tripType, destination, start, end, itinerary.toMarkdown(),
                        changeNote == null ? (tripId == null ? "首次生成" : "修改") : changeNote, json(itinerary), true),
                "AI");
        Trip trip = detail.trip();
        tripEditor.ensureGeoLater(trip.getId(), detail.version().getVersion());
        priceCheck.start(ToolContexts.user(toolContext), trip.getId(), prepared.targets());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tripId", trip.getId());
        out.put("version", detail.version().getVersion());
        out.put("status", trip.getStatus());
        Map<String, Object> card = tripCard(trip, detail.version().getVersion(), itinerary);
        card.put("checking", TripPriceCheck.checking(prepared.targets()));
        if (!prepared.targets().isEmpty()) {
            out.put("prices", "选中的酒店、航班正在后台补其他平台的价格，行程里会自动更新；回答里说一句价格还在比，不满意可以直接说或在行程里点换");
        }
        if (cards.emit("trip", card, summary(trip, itinerary))) {
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
    @Tool(description = "读取一个已保存行程的当前内容，修改行程前先读：每项的 id、每天的日期、stays 里住宿的 id 是局部修改工具定位用的。status 标着用户要保留、已预约、已去过的项不要动。")
    public Map<String, Object> getTrip(@ToolParam(description = "行程 id") long tripId, ToolContext toolContext) {
        TripService.TripDetail detail = tripService.get(ToolContexts.user(toolContext), tripId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tripId", tripId);
        out.put("title", detail.trip().getTitle());
        out.put("startDate", String.valueOf(detail.trip().getStartDate()));
        out.put("endDate", String.valueOf(detail.trip().getEndDate()));
        out.put("version", detail.version().getVersion());
        Object parsed = detail.version().getItinerary() == null ? null : parse(detail.version().getItinerary());
        if (parsed instanceof Itinerary itinerary) {
            out.put("itinerary", forModel(itinerary));
        } else if (parsed != null) {
            out.put("itinerary", parsed);
        } else {
            out.put("content", detail.version().getContent());
        }
        return out;
    }

    /** 用户上传的订单里的航班记进行程：按日期找到那一天，写进航班并标成已确认订单。不下单。 */
    @Tool(description = """
            把用户上传的机票行程单、订单截图里的航班记进行程，并标成已预约。用户明确同意"记进行程"后才调用，
            参数用附件里识别出的原值，看不清的不要猜。这天原来有航班会换掉；原来的航班标了已预约或保留时返回 error。""")
    public Map<String, Object> addBookedFlight(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "起飞日期 yyyy-MM-dd") String date,
            @ToolParam(description = "航班号，例如 CZ3101") String flightNo,
            @ToolParam(description = "起飞时间 HH:mm") String depTime,
            @ToolParam(description = "到达时间 HH:mm") String arrTime,
            @ToolParam(description = "出发机场和航站楼，例如：宝安机场 T3", required = false) String depAirport,
            @ToolParam(description = "到达机场和航站楼", required = false) String arrAirport,
            @ToolParam(description = "航空公司", required = false) String airline,
            @ToolParam(description = "订单上的票价（元），没有就不填", required = false) Integer price,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        try {
            int day = dayIndex(ctx, tripId, date);
            if (day < 0) {
                return Map.of("error", date + " 不在这个行程的日期里，先问用户是不是记错了行程或要不要改行程日期");
            }
            TripEditor.FlightPick pick = new TripEditor.FlightPick(flightNo == null ? null : flightNo.replace(" ", "").toUpperCase(),
                    airline, depTime, arrTime, depAirport, arrAirport, price, TripEditor.ORDER, null);
            TripEditor.FlightPreview preview = tripEditor.previewFlight(ctx, tripId, day, pick);
            tripEditor.saveFlight(ctx, tripId, day, pick, preview.replacesId());
            tripEditor.setBooking(ctx, tripId, preview.item().id(), Itinerary.CONFIRMED);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", "第 " + (day + 1) + " 天：" + preview.item().name() + "，已标为已预约");
            if (preview.replacesName() != null) {
                out.put("replaced", preview.replacesName());
            }
            if (!preview.conflicts().isEmpty()) {
                out.put("conflicts", preview.conflicts().stream().map(c -> c.name() + "：" + c.problem()).toList());
                out.put("hint", "把冲突告诉用户，问要不要顺便调整这些安排");
            }
            return out;
        } catch (BizException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 用户上传的酒店订单记进行程：按入住日期找到那段住宿，换成订单上的酒店并标成已确认订单。不下单。 */
    @Tool(description = """
            把用户上传的酒店订单里的酒店记进行程，并标成已预约。用户明确同意"记进行程"后才调用，参数用附件里识别出的原值。
            按入住日期找到行程里对应的那段住宿：同一家就直接标已预约，不同就换成订单上的酒店；行程原来没有住宿会新加一段。""")
    public Map<String, Object> addBookedStay(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "酒店名称，按订单原样") String hotel,
            @ToolParam(description = "入住日期 yyyy-MM-dd") String checkIn,
            @ToolParam(description = "离店日期 yyyy-MM-dd") String checkOut,
            @ToolParam(description = "酒店地址", required = false) String address,
            @ToolParam(description = "每晚价格（元），订单只有总价时除以晚数，没有就不填", required = false) Integer pricePerNight,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        try {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            if (!(parse(detail.version().getItinerary()) instanceof Itinerary itinerary)) {
                return Map.of("error", "这个行程是早期的纯文字行程，记不了住宿");
            }
            List<Itinerary.Stay> stays = itinerary.stays() == null ? List.of() : itinerary.stays();
            Itinerary.Stay match = stays.stream().filter(s -> s.checkIn() != null && s.checkOut() != null
                    && s.checkIn().compareTo(checkIn) <= 0 && s.checkOut().compareTo(checkIn) > 0).findFirst().orElse(null);
            if (match == null && !stays.isEmpty()) {
                return Map.of("error", "行程里没有覆盖 " + checkIn + " 入住的住宿，现有住宿："
                        + stays.stream().map(s -> s.name() + "（" + s.checkIn() + "–" + s.checkOut() + "）").toList()
                        + "。问用户是不是记错了行程");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            String stayId;
            if (match != null && sameHotel(match.name(), hotel)) {
                stayId = match.id();
                out.put("saved", "行程里本来就是「" + match.name() + "」，已标为已预约");
            } else {
                TripEditor.StayPick pick = new TripEditor.StayPick(hotel, address, null, null, null, null, pricePerNight,
                        TripEditor.ORDER, null, null);
                TripEditor.StayPreview preview = tripEditor.previewStay(ctx, tripId, match == null ? null : match.id(), pick);
                tripEditor.saveStay(ctx, tripId, match == null ? null : match.id(), pick, preview.replacesId());
                stayId = preview.stay().id();
                out.put("saved", (preview.replacesName() == null ? "加上住宿「" : "住宿从「" + preview.replacesName() + "」换成「")
                        + hotel + "」，已标为已预约");
                out.put("notes", preview.notes());
            }
            tripEditor.setBooking(ctx, tripId, stayId, Itinerary.CONFIRMED);
            if (match != null && (!match.checkIn().equals(checkIn) || !match.checkOut().equals(checkOut))) {
                out.put("datesDiffer", "订单是 " + checkIn + "–" + checkOut + "，行程里这段住宿是 " + match.checkIn() + "–"
                        + match.checkOut() + "，日期没有自动改，告诉用户");
            }
            return out;
        } catch (BizException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 日期在行程第几天（从 0 开始），不在行程里返回 -1。 */
    private int dayIndex(UserCtx ctx, long tripId, String date) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        if (!(parse(detail.version().getItinerary()) instanceof Itinerary itinerary)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个行程是早期的纯文字行程，记不了航班");
        }
        for (int i = 0; i < itinerary.days().size(); i++) {
            if (date != null && date.equals(itinerary.days().get(i).date())) {
                return i;
            }
        }
        return -1;
    }

    /** 酒店名去掉空格和括号后互相包含，就当是同一家。 */
    private static boolean sameHotel(String a, String b) {
        String x = a == null ? "" : a.replaceAll("[\\s()（）·]", "");
        String y = b == null ? "" : b.replaceAll("[\\s()（）·]", "");
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    /**
     * 给模型看的精简行程：每项只留时间、类型、名称、地址、说明、人均、链接和状态（保留 / 已预约 / 已去过），
     * 每天附上路上的合计；照片、坐标、各平台报价这些模型用不上，去掉省 token。
     */
    static Map<String, Object> forModel(Itinerary it) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> days = new ArrayList<>();
        for (Itinerary.Day d : it.days() == null ? List.<Itinerary.Day>of() : it.days()) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.date());
            putIf(day, "theme", d.theme());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Itinerary.Item i : d.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", i.id());
                putIf(item, "start", i.start());
                putIf(item, "end", i.end());
                item.put("type", i.type());
                item.put("name", i.name());
                putIf(item, "address", i.address());
                putIf(item, "note", i.note());
                putIf(item, "cost", i.cost());
                putIf(item, "url", i.url());
                List<String> flags = new ArrayList<>();
                if (Boolean.TRUE.equals(i.locked())) {
                    flags.add("用户要保留");
                }
                if (it.isBooked(i.id())) {
                    flags.add("已预约");
                }
                if (it.isDone(i.id())) {
                    flags.add("FOOD".equals(i.type()) ? "已吃过" : "已去过");
                }
                if (!flags.isEmpty()) {
                    item.put("status", String.join("、", flags));
                }
                items.add(item);
            }
            day.put("items", items);
            if (d.legs() != null && !d.legs().isEmpty()) {
                day.put("travelMinutes", d.legs().stream().mapToInt(Itinerary.Leg::minutes).sum());
            }
            days.add(day);
        }
        out.put("days", days);
        if (it.stays() != null && !it.stays().isEmpty()) {
            out.put("stays", it.stays().stream().map(st -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", st.id());
                m.put("hotel", st.name());
                m.put("checkIn", st.checkIn());
                m.put("checkOut", st.checkOut());
                putIf(m, "pricePerNight", st.price());
                if (it.isBooked(st.id())) {
                    m.put("status", "已预约");
                }
                return m;
            }).toList());
        }
        putIf(out, "tips", it.tips());
        out.put("transport", Itinerary.DRIVE.equals(it.transport()) ? "用户选了打车" : "地铁公交为主");
        return out;
    }

    private static void putIf(Map<String, Object> map, String key, Object value) {
        if (value != null && !(value instanceof String str && str.isBlank())) {
            map.put(key, value);
        }
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
                            blankToNull(p.address()), blankToNull(p.note()), p.cost(), webUrl(p.url()), null));
                }
                out.add(new Itinerary.Day(day.date(), blankToNull(day.theme()), items));
            }
        }
        return new Itinerary(out, blankToNull(tips));
    }

    /**
     * 模型填的住宿转成存储结构：按酒店名在这个会话最近的比价里找各平台报价和其他候选；找不到就沿用上一版同名酒店的报价，
     * 再没有就只记酒店名。
     */
    List<Itinerary.Stay> toStays(List<PlanStay> stays, String conversationId, Itinerary previous) {
        List<Itinerary.Stay> out = new ArrayList<>();
        if (stays == null) {
            return out;
        }
        for (PlanStay p : stays) {
            if (p == null || p.hotel() == null || p.hotel().isBlank()) {
                continue;
            }
            String id = "s" + (out.size() + 1);
            HotelQuoteBook.Found found = quoteBook.find(conversationId, p.hotel()).orElse(null);
            if (found != null) {
                Itinerary.StayOption best = option(found.entry());
                List<Itinerary.StayOption> others = found.comparison().hotels().stream()
                        .filter(e -> e != found.entry()).map(TripTools::option).toList();
                out.add(new Itinerary.Stay(id, found.entry().hotel(), p.checkIn(), p.checkOut(), null, best.price(),
                        best.platform(), best.url(), found.comparison().queriedAt(), best.quotes(), others,
                        null, null, null, null, null));
                continue;
            }
            Itinerary.Stay old = previous == null || previous.stays() == null ? null : previous.stays().stream()
                    .filter(st -> AmapPlaceService.similarName(p.hotel(), st.name())).findFirst().orElse(null);
            out.add(old == null
                    ? new Itinerary.Stay(id, p.hotel().trim(), p.checkIn(), p.checkOut(), null, null, null, null, null,
                    null, null, null, null, null, null, null)
                    : new Itinerary.Stay(id, old.name(), p.checkIn(), p.checkOut(), old.address(), old.price(),
                    old.platform(), old.url(), old.queriedAt(), old.quotes(), old.options(), old.poiId(), old.lng(),
                    old.lat(), old.photo(), old.rating()));
        }
        return out;
    }

    /** 同上，按名称把上一版"去过了 / 吃过了"的安排对到新版本上。 */
    static Itinerary carryDone(Itinerary itinerary, Itinerary previous) {
        if (previous == null || previous.done() == null) {
            return itinerary;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        previous.days().forEach(d -> d.items().stream().filter(i -> previous.isDone(i.id())).forEach(i -> names.add(i.name())));
        List<String> ids = new ArrayList<>();
        itinerary.days().forEach(d -> d.items().stream().filter(i -> names.contains(i.name())).forEach(i -> ids.add(i.id())));
        return itinerary.withDone(ids);
    }

    /** 沿用上一版的默认出行方式；单独改过的路段按起终点名称对到新版本上（id 会重新编号）。 */
    static Itinerary carryTransport(Itinerary itinerary, Itinerary previous) {
        if (previous == null || previous.transport() == null && previous.legModes() == null) {
            return itinerary;
        }
        java.util.Map<String, String> names = new java.util.HashMap<>();
        java.util.Map<String, String> byName = new java.util.HashMap<>();
        previous.days().forEach(d -> d.items().forEach(i -> names.put(i.id(), i.name())));
        if (previous.legModes() != null) {
            previous.legModes().forEach((k, v) -> {
                String[] ids = k.split(">", 2);
                if (ids.length == 2 && names.containsKey(ids[0]) && names.containsKey(ids[1])) {
                    byName.put(names.get(ids[0]) + ">" + names.get(ids[1]), v);
                }
            });
        }
        java.util.Map<String, String> modes = new java.util.HashMap<>();
        for (Itinerary.Day d : itinerary.days()) {
            for (int i = 0; i + 1 < d.items().size(); i++) {
                for (int j = i + 1; j < d.items().size(); j++) {
                    String mode = byName.get(d.items().get(i).name() + ">" + d.items().get(j).name());
                    if (mode != null) {
                        modes.put(d.items().get(i).id() + ">" + d.items().get(j).id(), mode);
                    }
                }
            }
        }
        return itinerary.withTransport(previous.transport(), modes);
    }

    /** 模型改版时 id 会重新编号，按名称把上一版"已预约"的安排和住宿对到新版本上。 */
    static Itinerary carryBooked(Itinerary itinerary, Itinerary previous) {
        if (previous == null || previous.booked() == null) {
            return itinerary;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        previous.days().forEach(d -> d.items().stream().filter(i -> previous.isBooked(i.id())).forEach(i -> names.add(i.name())));
        if (previous.stays() != null) {
            previous.stays().stream().filter(st -> previous.isBooked(st.id())).forEach(st -> names.add(st.name()));
        }
        List<String> ids = new ArrayList<>();
        itinerary.days().forEach(d -> d.items().stream().filter(i -> names.contains(i.name())).forEach(i -> ids.add(i.id())));
        if (itinerary.stays() != null) {
            itinerary.stays().stream().filter(st -> names.contains(st.name())).forEach(st -> ids.add(st.id()));
        }
        return itinerary.withBooked(ids);
    }

    /** 一家酒店的比价结果：有价格的各平台报价，最低价和平台。 */
    static Itinerary.StayOption option(HotelQuoteBook.Entry entry) {
        List<Itinerary.StayQuote> quotes = entry.quotes().stream().filter(q -> q.price() != null)
                .map(q -> new Itinerary.StayQuote(q.platform(), q.price(), q.url(), q.basis(), q.matchedName()))
                .toList();
        HotelPriceSource.Quote best = entry.quotes().stream().filter(q -> q.price() != null)
                .min(java.util.Comparator.comparing(HotelPriceSource.Quote::price)).orElse(null);
        return new Itinerary.StayOption(entry.hotel(), best == null ? null : best.price(),
                best == null ? null : best.platform(), best == null ? null : best.url(), quotes);
    }

    /** 改版前的结构化行程，读不到返回空。 */
    private Itinerary previous(ToolContext toolContext, long tripId) {
        try {
            String json = tripService.get(ToolContexts.user(toolContext), tripId, null).version().getItinerary();
            return json == null ? null : objectMapper.readValue(json, Itinerary.class);
        } catch (RuntimeException | JsonProcessingException e) {
            return null;
        }
    }

    /** 模型改版时不会带上坐标、照片和"保留"标记，按地点名从上一版沿用过来。 */
    static Itinerary carryOver(Itinerary itinerary, Itinerary previous) {
        if (previous == null || previous.days() == null) {
            return itinerary;
        }
        Map<String, Itinerary.Item> old = new LinkedHashMap<>();
        previous.days().forEach(d -> d.items().forEach(i -> old.putIfAbsent(i.name(), i)));
        List<Itinerary.Day> days = new ArrayList<>();
        for (Itinerary.Day day : itinerary.days()) {
            List<Itinerary.Item> items = new ArrayList<>();
            for (Itinerary.Item i : day.items()) {
                Itinerary.Item o = old.get(i.name());
                items.add(o == null ? i : new Itinerary.Item(i.id(), i.start(), i.end(), i.type(), i.name(),
                        i.address(), i.note(), i.cost(), i.url(), o.locked(), o.poiId(), o.lng(), o.lat(), o.photo(),
                        o.rating()));
            }
            days.add(new Itinerary.Day(day.date(), day.theme(), items));
        }
        return itinerary.withDays(days);
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
        card.put("stays", itinerary.stays());
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
        if (itinerary.stays() != null) {
            itinerary.stays().forEach(st -> out.append("；住 ").append(st.name())
                    .append(st.price() == null ? "" : " 约" + st.price() + "元/晚"));
        }
        return out.toString();
    }

    private String json(Itinerary itinerary) {
        try {
            return objectMapper.writeValueAsString(itinerary.resolveLegs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Itinerary.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /** 只留 http(s) 链接，别的（比如 javascript:）当没有，免得页面上点出问题。 */
    static String webUrl(String url) {
        String u = blankToNull(url);
        return u != null && u.matches("(?i)^https?://\\S+$") ? u : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 解析 yyyy-MM-dd，空串当作未填。 */
    private static LocalDate date(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
    }
}
