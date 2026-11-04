package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证对话里局部改行程：只换改过的天、不能动的项拦下来、加减天、改动摘要。 */
class TripPatchTest {

    /** 三天的行程：第二天的博物馆已预约，第三天的夜市用户要保留。 */
    private static Itinerary trip() {
        Itinerary.Day d1 = new Itinerary.Day("2026-11-03", "老城", List.of(
                item("d1-1", "09:00", "11:00", "SPOT", "老城墙"),
                item("d1-2", "12:00", "13:00", "FOOD", "面馆"),
                item("d1-3", "14:00", "17:00", "SPOT", "天山天池")));
        Itinerary.Day d2 = new Itinerary.Day("2026-11-04", "博物馆", List.of(
                item("d2-1", "09:30", "12:00", "SPOT", "新疆博物馆"),
                item("d2-2", "14:00", "16:00", "SPOT", "红山公园")));
        Itinerary.Day d3 = new Itinerary.Day("2026-11-05", null, List.of(
                new Itinerary.Item("d3-1", "19:00", "21:00", "FOOD", "大巴扎夜市", null, null, null, null, true)));
        Itinerary.Stay stay = new Itinerary.Stay("s1", "如家酒店", "2026-11-03", "2026-11-05", null, 200, "携程", null,
                null, null, null, null, null, null, null, null);
        return new Itinerary(List.of(d1, d2, d3), null, true, List.of(stay), List.of("d2-1"));
    }

    @Test
    void replacesOneDayAndKeepsOthersUntouched() {
        Itinerary before = trip();
        Itinerary.Day d1 = before.days().get(0);
        Itinerary.Day changed = new Itinerary.Day(d1.date(), d1.theme(), List.of(d1.items().get(0), d1.items().get(1),
                item("d1-3", "14:00", "17:00", "SPOT", "新疆美术馆")));

        TripPatch.Result r = TripPatch.apply(before, List.of(changed), null, null, null);

        assertThat(r.problems()).isEmpty();
        assertThat(r.changedDates()).containsExactly("2026-11-03");
        assertThat(r.itinerary().days().get(1)).isEqualTo(before.days().get(1));
        assertThat(r.itinerary().isBooked("d2-1")).isTrue();
        assertThat(TripPatch.changes(before, r.itinerary()))
                .containsExactly("第 1 天（11月3日）：天山天池 → 新疆美术馆");
    }

    @Test
    void refusesToDropBookedOrLockedItems() {
        Itinerary before = trip();
        Itinerary.Day d2 = new Itinerary.Day("2026-11-04", null, List.of(item(null, "10:00", "12:00", "SPOT", "科技馆")));

        TripPatch.Result r = TripPatch.apply(before, List.of(d2), null, null, null);
        assertThat(r.problems()).anyMatch(p -> p.contains("新疆博物馆") && p.contains("已预约"));

        TripPatch.Result shorter = TripPatch.apply(before, List.of(), LocalDate.parse("2026-11-03"),
                LocalDate.parse("2026-11-04"), null);
        assertThat(shorter.problems()).anyMatch(p -> p.contains("大巴扎夜市") && p.contains("不能去掉"));
    }

    @Test
    void addsDaysWithNewRangeAndRenumbersItems() {
        Itinerary before = trip();
        Itinerary.Day extra = new Itinerary.Day("2026-11-06", "喀纳斯", List.of(
                item(null, "08:00", "12:00", "TRANSPORT", "前往喀纳斯"),
                item("d1-1", "14:00", "18:00", "SPOT", "喀纳斯湖")));
        Itinerary.Stay s2 = new Itinerary.Stay("s2", "喀纳斯山庄", "2026-11-05", "2026-11-06", null, null, null, null,
                null, null, null, null, null, null, null, null);

        TripPatch.Result missing = TripPatch.apply(before, List.of(extra), null, null, null);
        assertThat(missing.problems()).anyMatch(p -> p.contains("startDate"));

        TripPatch.Result r = TripPatch.apply(before, List.of(extra), null, LocalDate.parse("2026-11-06"),
                List.of(before.stays().get(0), s2));
        assertThat(r.problems()).isEmpty();
        assertThat(r.rangeChanged()).isTrue();
        // 冲突的 d1-1 和空 id 都按第 4 天重新编号
        assertThat(r.itinerary().days().get(3).items()).extracting(Itinerary.Item::id).containsExactly("d4-1", "d4-2");
        assertThat(TripPatch.changes(before, r.itinerary())).contains(
                "行程日期：11月3日–11月5日（3 天）→ 11月3日–11月6日（4 天）",
                "新加第 4 天（11月6日）：喀纳斯湖",
                "加住宿 喀纳斯山庄（11月5日–11月6日）");
    }

    @Test
    void describesSwappedDaysAndTimeShift() {
        Itinerary before = trip();
        Itinerary.Day d1 = before.days().get(0);
        Itinerary.Day d2 = before.days().get(1);
        Itinerary unbooked = before.withBooked(null);

        TripPatch.Result swapped = TripPatch.apply(unbooked, List.of(new Itinerary.Day(d1.date(), d2.theme(), d2.items()),
                new Itinerary.Day(d2.date(), d1.theme(), d1.items())), null, null, null);
        assertThat(swapped.problems()).isEmpty();
        assertThat(TripPatch.changes(unbooked, swapped.itinerary())).containsExactly(
                "第 1 天（11月3日）：换成原来第 2 天（11月4日）的安排",
                "第 2 天（11月4日）：换成原来第 1 天（11月3日）的安排");

        Itinerary.Day later = new Itinerary.Day(d1.date(), d1.theme(), List.of(d1.items().get(0), d1.items().get(1),
                item("d1-3", "15:00", "18:30", "SPOT", "天山天池")));
        TripPatch.Result shifted = TripPatch.apply(before, List.of(later), null, null, null);
        assertThat(TripPatch.changes(before, shifted.itinerary())).containsExactly(
                "第 1 天（11月3日）：天山天池 14:00 → 15:00；当天结束 17:00 → 18:30");
    }

    @Test
    void ignoresUntouchedDaysWithRepeatedHotel() {
        Itinerary.Day day = new Itinerary.Day("2026-11-03", null, List.of(
                item("d1-1", "15:40", "16:00", "HOTEL", "如家酒店"),
                item("d1-2", "17:00", "19:00", "SPOT", "孔雀河"),
                item("d1-3", "20:30", "21:00", "HOTEL", "如家酒店")));
        Itinerary.Day other = new Itinerary.Day("2026-11-04", null, List.of(item("d2-1", "09:00", "12:00", "SPOT", "A")));
        Itinerary before = new Itinerary(List.of(day, other), null);

        TripPatch.Result r = TripPatch.apply(before, List.of(new Itinerary.Day("2026-11-04", null,
                List.of(item("d2-1", "09:00", "12:00", "SPOT", "B")))), null, null, null);
        assertThat(TripPatch.changes(before, r.itinerary())).containsExactly("第 2 天（11月4日）：A → B");
    }

    @Test
    void reportsOverlapOnlyForChangedDays() {
        Itinerary before = trip();
        Itinerary.Day bad = new Itinerary.Day("2026-11-03", null, List.of(item(null, "09:00", "12:00", "SPOT", "A"),
                item(null, "11:00", "13:00", "SPOT", "B")));

        assertThat(TripPatch.apply(before, List.of(bad), null, null, null).problems())
                .containsExactly("2026-11-03：A 和 B 时间重叠");

        // 原来就重叠的，改这天别的项时不拦
        Itinerary.Day saved = new Itinerary.Day("2026-11-03", null, List.of(item("d1-1", "09:00", "12:00", "SPOT", "A"),
                item("d1-2", "11:00", "13:00", "SPOT", "B")));
        Itinerary messy = before.withDays(List.of(saved, before.days().get(1), before.days().get(2)));
        Itinerary.Day edited = new Itinerary.Day("2026-11-03", null, List.of(saved.items().get(0), saved.items().get(1),
                item(null, "18:00", "19:00", "FOOD", "烤肉")));
        assertThat(TripPatch.apply(messy, List.of(edited), null, null, null).problems()).isEmpty();
    }

    private static Itinerary.Item item(String id, String start, String end, String type, String name) {
        return new Itinerary.Item(id, start, end, type, name, null, null, null, null, null);
    }
}
