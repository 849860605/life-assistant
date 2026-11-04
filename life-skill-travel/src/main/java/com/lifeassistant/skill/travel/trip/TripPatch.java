package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.place.AmapPlaceService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对话里局部改行程的纯计算：把改过的几天合进原行程（没提到的天原样保留），检查用户要保留、已预约、已去过的项没被动，
 * 再算出"改了什么"的摘要。不读库、不查地图，方便单测。
 */
final class TripPatch {

    /** 一天最多这么多项，和页面上手动加的上限一致 */
    static final int MAX_ITEMS_PER_DAY = 40;
    /** 行程最长天数，防止模型把日期写错成几年 */
    static final int MAX_DAYS = 60;
    /** 摘要里一天最多列这么多个地点名 */
    private static final int MAX_NAMES = 5;

    private TripPatch() {
    }

    /**
     * 合并结果：itinerary 是合好的行程（改过的天还没重算路程）；changedDates 是改过、新加的日期；
     * problems 不为空时不能保存；rangeChanged 表示行程日期变了。
     */
    record Result(Itinerary itinerary, Set<String> changedDates, List<String> problems, boolean rangeChanged) {
    }

    /**
     * 把 changed 里的几天合进 before：同一日期的整天换掉，start、end 给了就按新的日期范围排（多出来的日子必须在 changed 里，
     * 范围外的日子去掉）；stays 为空表示住宿不变。改过的天里没有 id 或 id 冲突的项重新编号。
     */
    static Result apply(Itinerary before, List<Itinerary.Day> changed, LocalDate start, LocalDate end,
                        List<Itinerary.Stay> stays) {
        List<String> problems = new ArrayList<>();
        Map<String, Itinerary.Day> old = new LinkedHashMap<>();
        before.days().forEach(d -> old.put(d.date(), d));
        Map<String, Itinerary.Day> fresh = new LinkedHashMap<>();
        for (Itinerary.Day d : changed == null ? List.<Itinerary.Day>of() : changed) {
            if (d == null || date(d.date()) == null) {
                problems.add("日期要写成 yyyy-MM-dd：" + (d == null ? "空" : d.date()));
                continue;
            }
            fresh.put(d.date(), d);
        }

        List<String> range = new ArrayList<>();
        boolean rangeChanged = start != null || end != null;
        if (rangeChanged) {
            LocalDate from = start != null ? start : date(before.days().get(0).date());
            LocalDate to = end != null ? end : date(before.days().get(before.days().size() - 1).date());
            if (from == null || to == null || to.isBefore(from)) {
                problems.add("结束日期不能早于开始日期");
                return new Result(before, Set.of(), problems, true);
            }
            if (from.plusDays(MAX_DAYS).isBefore(to)) {
                problems.add("行程最长 " + MAX_DAYS + " 天，检查一下日期");
                return new Result(before, Set.of(), problems, true);
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                range.add(d.toString());
            }
            rangeChanged = !range.equals(new ArrayList<>(old.keySet()));
        } else {
            range.addAll(old.keySet());
            fresh.keySet().stream().filter(d -> !old.containsKey(d)).forEach(d -> problems.add(d
                    + " 不在行程日期里；要加天或改日期，同时传新的 startDate、endDate"));
        }

        // 去掉的日子里不能有要保留、已预约、已去过的项
        for (Itinerary.Day d : before.days()) {
            if (!range.contains(d.date())) {
                d.items().stream().filter(i -> fixed(before, i) != null).forEach(i -> problems.add(d.date()
                        + " 的「" + i.name() + "」" + fixed(before, i) + "，这一天不能去掉"));
            }
        }

        Set<String> used = new HashSet<>();
        range.stream().filter(d -> !fresh.containsKey(d) && old.containsKey(d))
                .forEach(d -> old.get(d).items().forEach(i -> used.add(i.id())));
        List<Itinerary.Day> days = new ArrayList<>();
        Set<String> changedDates = new LinkedHashSet<>();
        for (int pos = 0; pos < range.size(); pos++) {
            String date = range.get(pos);
            Itinerary.Day was = old.get(date);
            Itinerary.Day now = fresh.get(date);
            if (now == null) {
                if (was == null) {
                    problems.add(date + " 还没有安排，新加的日子要在 days 里写上");
                } else {
                    days.add(was);
                }
                continue;
            }
            List<Itinerary.Item> items = now.items() == null ? List.of() : now.items();
            if (items.size() > MAX_ITEMS_PER_DAY) {
                problems.add(date + "：一天最多 " + MAX_ITEMS_PER_DAY + " 项");
            }
            if (was != null) {
                for (Itinerary.Item i : was.items()) {
                    String why = fixed(before, i);
                    if (why != null && items.stream().noneMatch(n -> sameName(n.name(), i.name()))) {
                        problems.add(date + " 的「" + i.name() + "」" + why + "，不能删或换；原样写进这一天");
                    }
                }
            }
            List<Itinerary.Item> renumbered = new ArrayList<>();
            int next = 1;
            for (Itinerary.Item i : items) {
                String id = i.id();
                if (id == null || used.contains(id)) {
                    do {
                        id = "d" + (pos + 1) + "-" + next++;
                    } while (used.contains(id));
                }
                used.add(id);
                renumbered.add(new Itinerary.Item(id, i.start(), i.end(), i.type(), i.name(), i.address(), i.note(),
                        i.cost(), i.url(), i.locked(), i.poiId(), i.lng(), i.lat(), i.photo(), i.rating()));
            }
            days.add(new Itinerary.Day(date, now.theme() != null ? now.theme() : was == null ? null : was.theme(),
                    renumbered, was != null && sameItems(was.items(), renumbered) ? was.legs() : null));
            changedDates.add(date);
        }

        // 只检查改过的天，而且只拦这次改出来的新问题：原来就有的小问题（比如计划航班和已出票航班重叠）不拦着这次修改，
        // 免得为了保存顺手删掉用户没让动的项
        LocalDate first = range.isEmpty() ? null : date(range.get(0));
        LocalDate last = range.isEmpty() ? null : date(range.get(range.size() - 1));
        Set<String> existing = new HashSet<>(new Itinerary(before.days().stream()
                .filter(d -> changedDates.contains(d.date())).toList(), null).problems(null, null));
        problems.addAll(new Itinerary(days.stream().filter(d -> changedDates.contains(d.date())).toList(), null)
                .problems(first, last).stream().filter(p -> !p.startsWith("days 为空") && !existing.contains(p)).toList());

        Itinerary merged = before.withDays(days);
        if (stays != null) {
            if (before.stays() != null) {
                before.stays().stream().filter(st -> before.isBooked(st.id()))
                        .filter(st -> stays.stream().noneMatch(n -> sameName(n.name(), st.name())))
                        .forEach(st -> problems.add("住宿「" + st.name() + "」已预约，不能去掉；原样写进 stays"));
            }
            merged = merged.withStays(stays);
        }
        for (Itinerary.Stay st : merged.stays() == null ? List.<Itinerary.Stay>of() : merged.stays()) {
            if (st.nights() == 0) {
                problems.add("住宿 " + st.name() + "：入住、离店日期要写成 yyyy-MM-dd，离店晚于入住");
            } else if (first != null && (st.checkIn().compareTo(first.toString()) < 0
                    || st.checkOut().compareTo(last.plusDays(1).toString()) > 0)) {
                problems.add("住宿 " + st.name() + "：日期不在行程 " + first + " 到 " + last + " 之内"
                        + (stays == null ? "，改了日期要在 stays 里重新写住宿" : ""));
            }
        }
        merged = TripTools.carryOver(merged, before);
        merged = TripTools.carryBooked(merged, before);
        merged = TripTools.carryDone(merged, before);
        merged = TripTools.carryTransport(merged, before);
        return new Result(merged, changedDates, problems, rangeChanged);
    }

    /** 这一项为什么不能动（用户要保留 / 已预约 / 已去过），能动返回 null。 */
    static String fixed(Itinerary itinerary, Itinerary.Item item) {
        if (Boolean.TRUE.equals(item.locked())) {
            return "是用户要保留的";
        }
        if (item.id() == null) {
            return null;
        }
        if (itinerary.isBooked(item.id())) {
            return "已预约";
        }
        if (itinerary.isDone(item.id())) {
            return "FOOD".equals(item.type()) ? "已经吃过了" : "已经去过了";
        }
        return null;
    }

    /**
     * 改动摘要：每个改过的天一行，例如"第 2 天（11月4日）：天山天池 → 新疆博物馆；当天结束 18:30 → 19:10"，
     * 再加日期范围和住宿的变化。没有变化返回空列表。
     */
    static List<String> changes(Itinerary before, Itinerary after) {
        List<String> out = new ArrayList<>();
        List<String> beforeDates = before.days().stream().map(Itinerary.Day::date).toList();
        List<String> afterDates = after.days().stream().map(Itinerary.Day::date).toList();
        if (!beforeDates.isEmpty() && !afterDates.isEmpty() && (!beforeDates.get(0).equals(afterDates.get(0))
                || !beforeDates.get(beforeDates.size() - 1).equals(afterDates.get(afterDates.size() - 1)))) {
            out.add("行程日期：" + md(beforeDates.get(0)) + "–" + md(beforeDates.get(beforeDates.size() - 1)) + "（"
                    + beforeDates.size() + " 天）→ " + md(afterDates.get(0)) + "–" + md(afterDates.get(afterDates.size() - 1))
                    + "（" + afterDates.size() + " 天）");
        }
        for (int i = 0; i < before.days().size(); i++) {
            Itinerary.Day d = before.days().get(i);
            if (!afterDates.contains(d.date())) {
                out.add("去掉" + label(i, d.date()) + "：" + names(d.items()));
            }
        }
        for (int i = 0; i < after.days().size(); i++) {
            Itinerary.Day d = after.days().get(i);
            int was = beforeDates.indexOf(d.date());
            if (was < 0) {
                out.add("新加" + label(i, d.date()) + "：" + names(d.items()));
                continue;
            }
            String line = dayChange(before, before.days().get(was), d);
            if (line != null) {
                out.add(label(i, d.date()) + "：" + line);
            }
        }
        out.addAll(stayChanges(before.stays(), after.stays()));
        return out;
    }

    /** 同一天改前改后的差别，没变化返回 null。 */
    private static String dayChange(Itinerary before, Itinerary.Day was, Itinerary.Day now) {
        if (sameItems(was.items(), now.items()) && sameDetails(was.items(), now.items())
                && Objects.equals(was.theme(), now.theme())) {
            return null;
        }
        List<String> wasNames = was.items().stream().map(Itinerary.Item::name).toList();
        List<String> nowNames = now.items().stream().map(Itinerary.Item::name).toList();
        // 整天换成了原来另一天的安排（对调两天）
        if (!wasNames.equals(nowNames)) {
            for (int k = 0; k < before.days().size(); k++) {
                Itinerary.Day other = before.days().get(k);
                if (!other.date().equals(was.date()) && !nowNames.isEmpty()
                        && other.items().stream().map(Itinerary.Item::name).toList().equals(nowNames)) {
                    return "换成原来第 " + (k + 1) + " 天（" + md(other.date()) + "）的安排";
                }
            }
        }
        List<String> parts = new ArrayList<>();
        Map<String, Itinerary.Item> wasById = new LinkedHashMap<>();
        was.items().forEach(i -> wasById.put(i.id(), i));
        List<String> removed = new ArrayList<>(wasNames);
        List<String> added = new ArrayList<>(nowNames);
        for (Itinerary.Item i : now.items()) {
            Itinerary.Item o = wasById.get(i.id());
            if (o != null && !o.name().equals(i.name()) && !nowNames.contains(o.name())
                    && !wasNames.contains(i.name()) && removed.contains(o.name()) && added.contains(i.name())) {
                parts.add(o.name() + " → " + i.name());
                removed.remove(o.name());
                added.remove(i.name());
            }
        }
        nowNames.forEach(removed::remove);
        wasNames.forEach(added::remove);
        if (!removed.isEmpty()) {
            parts.add("去掉 " + String.join("、", removed));
        }
        if (!added.isEmpty()) {
            parts.add("加入 " + String.join("、", added));
        }
        // 同名的项（入住、回酒店）一天里可能有好几个：先按 id 对，对不上再按名称对没用过的
        List<String> moved = new ArrayList<>();
        Set<Itinerary.Item> matched = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Itinerary.Item i : now.items()) {
            Itinerary.Item o = was.items().stream()
                    .filter(x -> !matched.contains(x) && x.id().equals(i.id()) && x.name().equals(i.name())).findFirst()
                    .orElseGet(() -> was.items().stream().filter(x -> !matched.contains(x) && x.name().equals(i.name()))
                            .findFirst().orElse(null));
            if (o == null) {
                continue;
            }
            matched.add(o);
            if (o.start() != null && i.start() != null && !o.start().equals(i.start())) {
                moved.add(i.name() + " " + o.start() + " → " + i.start());
            }
        }
        if (!moved.isEmpty()) {
            parts.add(moved.size() > 3 ? moved.size() + " 项时间调整" : String.join("，", moved));
        } else if (removed.isEmpty() && added.isEmpty() && parts.isEmpty() && !wasNames.equals(nowNames)) {
            parts.add("调整了顺序");
        }
        String wasEnd = endOf(was);
        String nowEnd = endOf(now);
        if (!parts.isEmpty() && wasEnd != null && nowEnd != null && !wasEnd.equals(nowEnd)) {
            parts.add("当天结束 " + wasEnd + " → " + nowEnd);
        }
        if (parts.isEmpty() && !Objects.equals(was.theme(), now.theme()) && now.theme() != null) {
            parts.add("主题改成「" + now.theme() + "」");
        }
        if (parts.isEmpty() && !sameDetails(was.items(), now.items())) {
            parts.add("改了说明或花费");
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    /** 住宿的变化：同一段换了酒店、新加、去掉、日期变了。 */
    private static List<String> stayChanges(List<Itinerary.Stay> was, List<Itinerary.Stay> now) {
        List<Itinerary.Stay> a = was == null ? List.of() : was;
        List<Itinerary.Stay> b = now == null ? List.of() : now;
        List<String> out = new ArrayList<>();
        List<Itinerary.Stay> left = new ArrayList<>(a);
        for (Itinerary.Stay st : b) {
            Itinerary.Stay same = left.stream().filter(o -> sameName(o.name(), st.name())).findFirst().orElse(null);
            if (same != null) {
                left.remove(same);
                if (!Objects.equals(same.checkIn(), st.checkIn()) || !Objects.equals(same.checkOut(), st.checkOut())) {
                    out.add("住宿 " + st.name() + "：" + md(same.checkIn()) + "–" + md(same.checkOut()) + " → "
                            + md(st.checkIn()) + "–" + md(st.checkOut()));
                }
                continue;
            }
            Itinerary.Stay swapped = left.stream().filter(o -> Objects.equals(o.checkIn(), st.checkIn())
                    || Objects.equals(o.id(), st.id())).findFirst().orElse(null);
            String price = st.price() == null ? "" : "，每晚 ¥" + st.price() + (st.platform() == null ? "" : "（" + st.platform() + "）");
            if (swapped != null) {
                left.remove(swapped);
                out.add("住宿 " + swapped.name() + " → " + st.name() + price);
            } else {
                out.add("加住宿 " + st.name() + "（" + md(st.checkIn()) + "–" + md(st.checkOut()) + "）" + price);
            }
        }
        left.forEach(o -> out.add("去掉住宿 " + o.name()));
        return out;
    }

    /** 两组项的地点和时间都一样（路程不用重算）。 */
    private static boolean sameItems(List<Itinerary.Item> a, List<Itinerary.Item> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Itinerary.Item x = a.get(i);
            Itinerary.Item y = b.get(i);
            if (!x.id().equals(y.id()) || !x.name().equals(y.name()) || !Objects.equals(x.start(), y.start())
                    || !Objects.equals(x.end(), y.end())) {
                return false;
            }
        }
        return true;
    }

    /** 说明、花费、类型、地址都一样。 */
    private static boolean sameDetails(List<Itinerary.Item> a, List<Itinerary.Item> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Itinerary.Item x = a.get(i);
            Itinerary.Item y = b.get(i);
            if (!Objects.equals(x.note(), y.note()) || !Objects.equals(x.cost(), y.cost())
                    || !Objects.equals(x.type(), y.type()) || !Objects.equals(x.address(), y.address())
                    || !Objects.equals(x.end(), y.end())) {
                return false;
            }
        }
        return true;
    }

    /** 当天最后一项的结束时间（没有结束时间用开始时间）。 */
    private static String endOf(Itinerary.Day day) {
        String last = null;
        for (Itinerary.Item i : day.items()) {
            String t = i.end() != null ? i.end() : i.start();
            if (t != null && (last == null || t.compareTo(last) > 0)) {
                last = t;
            }
        }
        return last;
    }

    /** 地点名单：最多列几个，多的写"等 N 项"。 */
    private static String names(List<Itinerary.Item> items) {
        List<String> names = items.stream().filter(i -> !"TRANSPORT".equals(i.type())).map(Itinerary.Item::name).toList();
        if (names.isEmpty()) {
            names = items.stream().map(Itinerary.Item::name).toList();
        }
        return names.size() <= MAX_NAMES ? String.join("、", names)
                : String.join("、", names.subList(0, MAX_NAMES)) + " 等 " + names.size() + " 项";
    }

    /** "第 2 天（11月4日）" */
    private static String label(int index, String date) {
        return "第 " + (index + 1) + " 天（" + md(date) + "）";
    }

    /** yyyy-MM-dd 写成"11月4日"，解析不了原样返回。 */
    static String md(String date) {
        LocalDate d = date(date);
        return d == null ? String.valueOf(date) : d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    private static boolean sameName(String a, String b) {
        return a != null && b != null && (a.equals(b) || AmapPlaceService.similarName(a, b));
    }

    private static LocalDate date(String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
