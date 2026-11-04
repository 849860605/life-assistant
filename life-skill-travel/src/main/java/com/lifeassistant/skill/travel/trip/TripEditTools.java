package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.auth.ToolContexts;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.skill.travel.hotel.HotelQuoteBook;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给模型用的局部改行程工具：换、删、加某几项，调某天顺序，对调两天，重写某几天（可加减天），换酒店。
 * 每次只改相关的部分，存成一个新版本，发一张改动卡（写清改了什么，能撤销），不用把整份行程重新生成。
 */
@Component
@RequiredArgsConstructor
public class TripEditTools {

    private static final String READ_FIRST = "先调用 getTrip 读出最新内容，用里面的 id 再改";

    private final TripService tripService;
    private final TripEditor tripEditor;
    private final TripTools tripTools;
    private final HotelQuoteBook quoteBook;
    private final CardHub cards;
    private final ObjectMapper objectMapper;
    private final TripPriceCheck priceCheck;

    /** 换掉的一项：按 id 定位，新地点和可选的新时间、说明。 */
    public record ItemChange(
            @ToolParam(description = "要换的那项的 id（getTrip 返回的，例如 d2-3）") String itemId,
            @ToolParam(description = "新地点的正式名称，一项只写一个地点") String name,
            @ToolParam(description = "新的开始时间 HH:mm，不改不填", required = false) String start,
            @ToolParam(description = "新的结束时间 HH:mm，不改不填", required = false) String end,
            @ToolParam(description = "类型 SPOT / FOOD / TRANSPORT / FREE，不改不填", required = false) String type,
            @ToolParam(description = "地址或所在区域", required = false) String address,
            @ToolParam(description = "一句说明：玩什么、怎么去、注意什么", required = false) String note,
            @ToolParam(description = "人均花费（元），不知道不填", required = false) Integer cost,
            @ToolParam(description = "餐厅填 dianpingUrl，没有不填", required = false) String url) {
    }

    /** 加进某天的一项。 */
    public record NewItem(
            @ToolParam(description = "加到哪天 yyyy-MM-dd") String date,
            @ToolParam(description = "开始时间 HH:mm；不填就自动放（吃饭放空着的饭点，其他接在当天最后）", required = false) String start,
            @ToolParam(description = "结束时间 HH:mm", required = false) String end,
            @ToolParam(description = "类型 SPOT / FOOD / TRANSPORT / FREE") String type,
            @ToolParam(description = "地点的正式名称，一项只写一个地点") String name,
            @ToolParam(description = "地址或所在区域", required = false) String address,
            @ToolParam(description = "一句说明", required = false) String note,
            @ToolParam(description = "人均花费（元），不知道不填", required = false) Integer cost,
            @ToolParam(description = "餐厅填 dianpingUrl，没有不填", required = false) String url) {
    }

    /** 换掉某几项（时间默认不变），一次存成一个版本。 */
    @Tool(description = """
            修改已保存的行程：把某几项换成别的地点（例如"第二天下午换成室内的""这家餐厅换一家"）。
            只改这几项，其余不动，时间默认沿用原来的。itemId 用 getTrip 返回的 id。换酒店用 changeTripHotel。
            用户说换掉的原因是去过了（VISITED）或不感兴趣（DISLIKE）时填 reason，以后规划会避开。""")
    public Map<String, Object> replaceTripItems(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "要换的项") List<ItemChange> changes,
            @ToolParam(description = "原因：VISITED 去过了 / DISLIKE 不感兴趣；只是想换不填", required = false) String reason,
            @ToolParam(description = "这次改了什么，一句话", required = false) String changeNote,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        if (changes == null || changes.isEmpty()) {
            return Map.of("error", "changes 为空");
        }
        Itinerary it = current(ctx, tripId);
        if (it == null) {
            return Map.of("error", "这个行程是早期的纯文字行程，改不了单项，用 saveTripPlan 重新生成");
        }
        Map<String, Itinerary.Day> days = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        List<String> replacedNames = new ArrayList<>();
        for (ItemChange c : changes) {
            Located at = locate(it, days, c == null ? null : c.itemId());
            if (at == null || c.name() == null || c.name().isBlank()) {
                problems.add(at == null ? "找不到 id 为 " + (c == null ? null : c.itemId()) + " 的项，" + READ_FIRST
                        : c.itemId() + "：name 不能为空");
                continue;
            }
            Itinerary.Item old = at.item();
            String why = TripPatch.fixed(it, old);
            if (why != null) {
                problems.add("「" + old.name() + "」" + why + "，不能换");
                continue;
            }
            if ("HOTEL".equals(old.type())) {
                problems.add("「" + old.name() + "」是住宿，换酒店用 changeTripHotel");
                continue;
            }
            String type = type(c.type(), old.type());
            boolean samePlace = c.name().trim().equals(old.name());
            Itinerary.Item fresh = new Itinerary.Item(old.id(), text(c.start(), old.start()), text(c.end(), old.end()),
                    type, c.name().trim(), samePlace ? text(c.address(), old.address()) : blank(c.address()),
                    samePlace ? text(c.note(), old.note()) : blank(c.note()), c.cost() != null ? c.cost() : samePlace ? old.cost() : null,
                    TripTools.webUrl(c.url()) != null ? TripTools.webUrl(c.url()) : samePlace ? old.url() : null, null,
                    samePlace ? old.poiId() : null, samePlace ? old.lng() : null, samePlace ? old.lat() : null,
                    samePlace ? old.photo() : null, samePlace ? old.rating() : null);
            days.put(at.day().date(), withItem(at.day(), old.id(), fresh));
            if (!samePlace) {
                replacedNames.add(old.name());
            }
        }
        if (!problems.isEmpty()) {
            return rejected(problems);
        }
        TripEditor.Reason why = reason(reason);
        replacedNames.forEach(n -> tripEditor.remember(ctx, n, why));
        return result(ctx, tripId, tripEditor.patch(ctx, tripId, sorted(days.values()), null, null, null, changeNote));
    }

    /** 删掉某几项，一次存成一个版本。 */
    @Tool(description = """
            修改已保存的行程：删掉某几项（例如"第一天删掉一个景点""不去 X 了"），其余不动。itemId 用 getTrip 返回的 id。
            要去掉整天用 rewriteTripDays。用户说原因是去过了（VISITED）或不感兴趣（DISLIKE）时填 reason。""")
    public Map<String, Object> removeTripItems(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "要删的项的 id") List<String> itemIds,
            @ToolParam(description = "原因：VISITED 去过了 / DISLIKE 不感兴趣；没说不填", required = false) String reason,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of("error", "itemIds 为空");
        }
        Itinerary it = current(ctx, tripId);
        if (it == null) {
            return Map.of("error", "这个行程是早期的纯文字行程，改不了单项，用 saveTripPlan 重新生成");
        }
        Map<String, Itinerary.Day> days = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String id : itemIds) {
            Located at = locate(it, days, id);
            if (at == null) {
                problems.add("找不到 id 为 " + id + " 的项，" + READ_FIRST);
                continue;
            }
            String why = TripPatch.fixed(it, at.item());
            if (why != null) {
                problems.add("「" + at.item().name() + "」" + why + "，不能删");
                continue;
            }
            Itinerary.Day day = withItem(at.day(), id, null);
            if (day.items().isEmpty()) {
                problems.add(at.day().date() + " 就剩这些安排了，要去掉整天用 rewriteTripDays");
                continue;
            }
            days.put(day.date(), day);
            removed.add(at.item().name());
        }
        if (!problems.isEmpty()) {
            return rejected(problems);
        }
        TripEditor.Reason why = reason(reason);
        removed.forEach(n -> tripEditor.remember(ctx, n, why));
        return result(ctx, tripId, tripEditor.patch(ctx, tripId, sorted(days.values()), null, null, null,
                "删掉" + String.join("、", removed.stream().map(n -> "「" + n + "」").toList())));
    }

    /** 往某几天加项，一次存成一个版本。 */
    @Tool(description = """
            修改已保存的行程：往某天加一两项（例如"第三天加个夜市""加一顿火锅"），其余不动。
            不写开始时间就自动放：吃饭放进空着的饭点，其他接在当天最后。要加新的日子用 rewriteTripDays。""")
    public Map<String, Object> addTripItems(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "要加的项") List<NewItem> items,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        if (items == null || items.isEmpty()) {
            return Map.of("error", "items 为空");
        }
        Itinerary it = current(ctx, tripId);
        if (it == null) {
            return Map.of("error", "这个行程是早期的纯文字行程，改不了单项，用 saveTripPlan 重新生成");
        }
        Map<String, Itinerary.Day> days = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (NewItem n : items) {
            if (n == null || n.name() == null || n.name().isBlank()) {
                problems.add("有一项没写地点名");
                continue;
            }
            Itinerary.Day day = days.get(n.date());
            if (day == null) {
                day = it.days().stream().filter(d -> d.date().equals(n.date())).findFirst().orElse(null);
            }
            if (day == null) {
                problems.add(n.date() + " 不在行程日期里；要加新的日子用 rewriteTripDays");
                continue;
            }
            String type = type(n.type(), "SPOT");
            if ("HOTEL".equals(type)) {
                problems.add("住宿用 changeTripHotel 换，或者用 rewriteTripDays 连 stays 一起写");
                continue;
            }
            String start = blank(n.start());
            String end = blank(n.end());
            if (start == null) {
                String[] slot = TripEditor.slot(day.items(), type);
                start = slot[0];
                end = end == null ? slot[1] : end;
            }
            List<Itinerary.Item> list = new ArrayList<>(day.items());
            // id 留空，合并时按这一天重新编号
            list.add(new Itinerary.Item(null, start, end, type, n.name().trim(), blank(n.address()), blank(n.note()),
                    n.cost(), TripTools.webUrl(n.url()), null));
            list.sort(java.util.Comparator.comparing(i -> i.start() == null ? "99:99" : i.start()));
            days.put(day.date(), new Itinerary.Day(day.date(), day.theme(), list, day.legs()));
        }
        if (!problems.isEmpty()) {
            return rejected(problems);
        }
        return result(ctx, tripId, tripEditor.patch(ctx, tripId, sorted(days.values()), null, null, null,
                "加入" + String.join("、", items.stream().map(n -> "「" + n.name() + "」").toList())));
    }

    /** 调整某天的顺序：时间段按先后不动，依次分给新顺序里的项。 */
    @Tool(description = """
            修改已保存的行程：调整某一天里各项的先后顺序（例如"先去 B 再去 A"）。itemIds 写这一天全部项的新顺序，
            原来的时间段按先后依次分给新顺序里的项；用户要保留、已预约的项不能挪位置。""")
    public Map<String, Object> reorderTripDay(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "哪天 yyyy-MM-dd") String date,
            @ToolParam(description = "这一天全部项的 id，按新顺序") List<String> itemIds,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        Itinerary it = current(ctx, tripId);
        Itinerary.Day day = it == null ? null : it.days().stream().filter(d -> d.date().equals(date)).findFirst().orElse(null);
        if (day == null) {
            return Map.of("error", date + " 不在行程里，" + READ_FIRST);
        }
        List<Itinerary.Item> old = day.items();
        if (itemIds == null || itemIds.size() != old.size()
                || !new HashSet<>(itemIds).equals(old.stream().map(Itinerary.Item::id).collect(java.util.stream.Collectors.toSet()))) {
            return Map.of("error", "itemIds 要写这一天全部项的 id，一个不多一个不少；" + READ_FIRST);
        }
        List<Itinerary.Item> reordered = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int k = 0; k < itemIds.size(); k++) {
            String id = itemIds.get(k);
            Itinerary.Item i = old.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            Itinerary.Item slot = old.get(k);
            String why = TripPatch.fixed(it, i);
            if (why != null && !slot.id().equals(i.id())) {
                problems.add("「" + i.name() + "」" + why + "，不能挪位置");
            }
            reordered.add(new Itinerary.Item(i.id(), slot.start(), slot.end(), i.type(), i.name(), i.address(),
                    i.note(), i.cost(), i.url(), i.locked(), i.poiId(), i.lng(), i.lat(), i.photo(), i.rating()));
        }
        if (!problems.isEmpty()) {
            return rejected(problems);
        }
        return result(ctx, tripId, tripEditor.patch(ctx, tripId, List.of(new Itinerary.Day(day.date(), day.theme(), reordered)),
                null, null, null, "调整 " + TripPatch.md(date) + " 的顺序"));
    }

    /** 对调两天的安排（各项时间不变），一次存成一个版本。 */
    @Tool(description = """
            修改已保存的行程：把两天的安排整天对调（例如"第三天和第四天换一下""下雨那天和后一天换"），各项时间不变。
            有已预约、用户要保留、已去过的项的那天不能对调。""")
    public Map<String, Object> swapTripDays(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "一天 yyyy-MM-dd") String dateA,
            @ToolParam(description = "另一天 yyyy-MM-dd") String dateB,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        Itinerary it = current(ctx, tripId);
        Itinerary.Day a = it == null ? null : it.days().stream().filter(d -> d.date().equals(dateA)).findFirst().orElse(null);
        Itinerary.Day b = it == null ? null : it.days().stream().filter(d -> d.date().equals(dateB)).findFirst().orElse(null);
        if (a == null || b == null || a == b) {
            return Map.of("error", "两个日期都要在行程里、而且不同；" + READ_FIRST);
        }
        List<String> problems = new ArrayList<>();
        for (Itinerary.Item i : concat(a.items(), b.items())) {
            String why = TripPatch.fixed(it, i);
            if (why != null) {
                problems.add("「" + i.name() + "」" + why + "，这天不能和别的天对调");
            }
        }
        if (!problems.isEmpty()) {
            return rejected(problems);
        }
        return result(ctx, tripId, tripEditor.patch(ctx, tripId, List.of(
                        new Itinerary.Day(a.date(), b.theme(), b.items()), new Itinerary.Day(b.date(), a.theme(), a.items())),
                null, null, null, "对调 " + TripPatch.md(dateA) + " 和 " + TripPatch.md(dateB)));
    }

    /** 重写某几天（其余天不动），可以顺带改行程日期、加减天、改住宿。 */
    @Tool(description = """
            修改已保存的行程：整天重排某几天（例如"第二天太赶了重新排""加一天去科莫多""少玩一天"），没写到的天原样保留。
            days 只写要重排或新加的那几天（每天写完整的安排，规则和 saveTripPlan 一样）；
            加天、减天、改出发日期时同时传新的 startDate、endDate：范围里新多出来的日子必须写在 days 里，范围外的日子会去掉。
            住宿要跟着变（加了一段新的地方、日期变了）时传 stays，写完整的住宿列表（酒店名用比价结果里的）；住宿不变不传。
            用户要保留、已预约、已去过的项要原样写进对应的那天。""")
    public Map<String, Object> rewriteTripDays(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "要重排或新加的那几天") List<TripTools.PlanDay> days,
            @ToolParam(description = "新的开始日期 yyyy-MM-dd；日期范围不变不填", required = false) String startDate,
            @ToolParam(description = "新的结束日期 yyyy-MM-dd；日期范围不变不填", required = false) String endDate,
            @ToolParam(description = "完整的住宿列表；住宿不变不填", required = false) List<TripTools.PlanStay> stays,
            @ToolParam(description = "这次改了什么，一句话", required = false) String changeNote,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        LocalDate start;
        LocalDate end;
        try {
            start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate.trim());
            end = endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate.trim());
        } catch (DateTimeParseException e) {
            return Map.of("error", "日期要写成 yyyy-MM-dd");
        }
        Itinerary it = current(ctx, tripId);
        if (it == null) {
            return Map.of("error", "这个行程是早期的纯文字行程，用 saveTripPlan 重新生成");
        }
        // 模型写的天先按 saveTripPlan 的规则转换，id 留空，合并时按所在的天重新编号
        List<Itinerary.Day> fresh = TripTools.toItinerary(days, null).days().stream()
                .map(d -> new Itinerary.Day(d.date(), d.theme(), d.items().stream()
                        .map(i -> new Itinerary.Item(null, i.start(), i.end(), i.type(), i.name(), i.address(), i.note(),
                                i.cost(), i.url(), null)).toList()))
                .toList();
        CallRecorder.Scope scope = CallRecorder.current();
        String conversationId = scope == null ? null : scope.conversationId();
        List<Itinerary.Stay> newStays = null;
        if (stays != null && !stays.isEmpty()) {
            newStays = tripTools.toStays(stays, conversationId, it);
        }
        // 新写的航班按查过的填价格；只查过一个平台的酒店、航班保存后后台补比价
        TripPriceCheck.Prepared prepared = priceCheck.prepare(new Itinerary(fresh, null).withStays(newStays), conversationId);
        Map<String, Object> out = result(ctx, tripId, tripEditor.patch(ctx, tripId, prepared.itinerary().days(), start, end,
                newStays, changeNote));
        if (!out.containsKey("error")) {
            priceCheck.start(ctx, tripId, prepared.targets());
        }
        return out;
    }

    /** 换某段住宿的酒店：价格、预订链接从这个会话最近的比价结果里取。 */
    @Tool(description = """
            修改已保存的行程：换酒店（例如"酒店换便宜点的""换成 X 酒店"）。先用 findAndCompareHotels / compareHotelPrices
            比价，hotel 原样用比价结果里的酒店名，价格和链接后端自动带上；用户点名的酒店没比过价也可以，只记名称。
            行程有好几段住宿时传 stayId（getTrip 的 stays 里的 id）；只有一段可以不传。已预约的住宿不能换。""")
    public Map<String, Object> changeTripHotel(
            @ToolParam(description = "行程 id") long tripId,
            @ToolParam(description = "新酒店的名称") String hotel,
            @ToolParam(description = "要换的那段住宿的 id，例如 s1；只有一段住宿时不填", required = false) String stayId,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        if (hotel == null || hotel.isBlank()) {
            return Map.of("error", "hotel 不能为空");
        }
        CallRecorder.Scope scope = CallRecorder.current();
        HotelQuoteBook.Found found = quoteBook.find(scope == null ? null : scope.conversationId(), hotel).orElse(null);
        TripEditor.StayPick pick;
        if (found != null) {
            Itinerary.StayOption best = TripTools.option(found.entry());
            pick = new TripEditor.StayPick(found.entry().hotel(), null, null, null, null, null, best.price(),
                    best.platform(), best.url(), best.quotes());
        } else {
            pick = new TripEditor.StayPick(hotel.trim(), null, null, null, null, null, null, null, null, null);
        }
        try {
            return result(ctx, tripId, tripEditor.patchStay(ctx, tripId, stayId == null || stayId.isBlank() ? null : stayId.trim(), pick));
        } catch (BizException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 把改动结果交给模型：没保存时返回原因；保存了就发改动卡，返回改动摘要。 */
    private Map<String, Object> result(UserCtx ctx, long tripId, TripEditor.Patched patched) {
        if (!patched.problems().isEmpty()) {
            return rejected(patched.problems());
        }
        Trip trip = patched.after().trip();
        int version = patched.after().version().getVersion();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("tripId", tripId);
        card.put("title", trip.getTitle());
        card.put("status", trip.getStatus());
        card.put("fromVersion", patched.fromVersion());
        card.put("version", version);
        card.put("changes", patched.changes());
        card.put("dates", patched.dates());
        String summary = "改动卡：《" + trip.getTitle() + "》tripId=" + tripId + " 第 " + patched.fromVersion() + " 版 → 第 "
                + version + " 版：" + String.join("；", patched.changes());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tripId", tripId);
        out.put("version", version);
        out.put("changes", patched.changes());
        if (cards.emit("trip_change", card, summary)) {
            out.put("card", "改动已作为卡片展示给用户（写了改了什么，能撤销、能点开看行程）。回答里用一两句说改了什么、为什么这样改，"
                    + "不要再写整份行程，也不要逐条重复卡片上的改动");
        }
        return out;
    }

    private static Map<String, Object> rejected(List<String> problems) {
        return Map.of("error", "没有保存", "problems", problems, "hint", "按 problems 改好再调；id 对不上时" + READ_FIRST);
    }

    /** 当前版本的结构化行程，早期纯文字行程返回 null。 */
    private Itinerary current(UserCtx ctx, long tripId) {
        String json = tripService.get(ctx, tripId, null).version().getItinerary();
        if (json == null) {
            return null;
        }
        try {
            Itinerary it = objectMapper.readValue(json, Itinerary.class);
            return it.days() == null ? null : it;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 某项在哪一天（同一轮里已经改过的那天用改过的）。 */
    private record Located(Itinerary.Day day, Itinerary.Item item) {
    }

    private static Located locate(Itinerary it, Map<String, Itinerary.Day> edited, String itemId) {
        if (itemId == null) {
            return null;
        }
        for (Itinerary.Day d : it.days()) {
            Itinerary.Day day = edited.getOrDefault(d.date(), d);
            for (Itinerary.Item i : day.items()) {
                if (itemId.equals(i.id())) {
                    return new Located(day, i);
                }
            }
        }
        return null;
    }

    /** 这一天把 id 那项换成 replacement（为空就删掉）。 */
    private static Itinerary.Day withItem(Itinerary.Day day, String id, Itinerary.Item replacement) {
        List<Itinerary.Item> items = new ArrayList<>();
        for (Itinerary.Item i : day.items()) {
            if (!i.id().equals(id)) {
                items.add(i);
            } else if (replacement != null) {
                items.add(replacement);
            }
        }
        items.sort(java.util.Comparator.comparing(i -> i.start() == null ? "99:99" : i.start()));
        return new Itinerary.Day(day.date(), day.theme(), items, day.legs());
    }

    private static List<Itinerary.Day> sorted(java.util.Collection<Itinerary.Day> days) {
        return days.stream().sorted(java.util.Comparator.comparing(Itinerary.Day::date)).toList();
    }

    private static List<Itinerary.Item> concat(List<Itinerary.Item> a, List<Itinerary.Item> b) {
        List<Itinerary.Item> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static TripEditor.Reason reason(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason.trim().toUpperCase()) {
            case "VISITED" -> TripEditor.Reason.VISITED;
            case "DISLIKE" -> TripEditor.Reason.DISLIKE;
            default -> null;
        };
    }

    private static String type(String wanted, String fallback) {
        String t = wanted == null ? null : wanted.trim().toUpperCase();
        return t != null && Itinerary.TYPES.contains(t) ? t : fallback;
    }

    private static String text(String wanted, String fallback) {
        String t = blank(wanted);
        return t != null ? t : fallback;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
