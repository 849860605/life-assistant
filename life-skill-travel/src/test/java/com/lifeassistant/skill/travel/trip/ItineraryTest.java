package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证模型填的逐日安排：编号、类型兜底、错误检查和生成的正文。 */
class ItineraryTest {

    private static final LocalDate START = LocalDate.parse("2026-03-10");
    private static final LocalDate END = LocalDate.parse("2026-03-11");

    @Test
    void numbersItemsAndRendersMarkdown() {
        Itinerary it = TripTools.toItinerary(List.of(new TripTools.PlanDay("2026-03-10", "熊猫 + 市区", List.of(
                item("09:00", "12:00", "spot", "大熊猫基地"),
                new TripTools.PlanItem("12:30", "13:30", "FOOD", "陈麻婆豆腐", "青华路", "招牌麻婆豆腐", 85,
                        "http://dpurl.cn/x"),
                item("14:00", "16:00", "博物馆", "人民公园")))), "带好身份证");

        assertThat(it.problems(START, END)).isEmpty();
        assertThat(it.days().get(0).items()).extracting(Itinerary.Item::id).containsExactly("d1-1", "d1-2", "d1-3");
        assertThat(it.days().get(0).items().get(2).type()).isEqualTo("SPOT");
        assertThat(it.toMarkdown())
                .contains("## D1 3月10日 周二 · 熊猫 + 市区")
                .contains("- 12:30–13:30 [**陈麻婆豆腐**](http://dpurl.cn/x)（青华路） — 招牌麻婆豆腐 · 人均 ¥85")
                .contains("## 注意事项");
    }

    @Test
    void reportsOverlapAndDatesOutOfRange() {
        Itinerary it = TripTools.toItinerary(List.of(
                new TripTools.PlanDay("2026-03-10", null, List.of(item("09:00", "12:00", "SPOT", "A"),
                        item("11:00", "13:00", "SPOT", "B"))),
                new TripTools.PlanDay("2026-03-15", null, List.of(item("9:00", "8:00", "SPOT", "C")))), null);

        assertThat(it.problems(START, END)).containsExactly(
                "2026-03-10：A 和 B 时间重叠",
                "2026-03-15：不在行程日期 2026-03-10 到 2026-03-11 之内",
                "2026-03-15 C：结束时间早于开始时间");
    }

    @Test
    void staysCountNightsAndPickTheRightHotelOnSwitchDay() {
        Itinerary.Stay a = stay("s1", "钟楼酒店", "2026-03-10", "2026-03-12");
        Itinerary.Stay b = stay("s2", "华清宫酒店", "2026-03-12", "2026-03-13");

        assertThat(a.nights()).isEqualTo(2);
        assertThat(a.covers("2026-03-12")).isTrue();
        assertThat(a.covers("2026-03-13")).isFalse();
        // 换酒店那天：上午还在前一家，下午算新的一家
        assertThat(TripGeo.stayFor(List.of(a, b), "2026-03-12", "09:00")).isSameAs(a);
        assertThat(TripGeo.stayFor(List.of(a, b), "2026-03-12", "21:00")).isSameAs(b);
        assertThat(TripGeo.stayFor(List.of(a, b), "2026-03-14", "21:00")).isNull();

        Itinerary it = TripTools.toItinerary(List.of(new TripTools.PlanDay("2026-03-10", null,
                List.of(item("09:00", "10:00", "SPOT", "A")))), null).withStays(List.of(a));
        assertThat(it.toMarkdown()).contains("## 住宿").contains("（2 晚）[**钟楼酒店**](https://x)")
                .contains("约 ¥300/晚（携程，以预订页为准）");
    }

    @Test
    void addedMealGoesIntoAFreeGapInsteadOfOverlapping() {
        List<Itinerary.Item> day = List.of(
                new Itinerary.Item("d1-1", "09:00", "11:30", "SPOT", "A", null, null, null, null, null),
                new Itinerary.Item("d1-2", "12:30", "13:30", "SPOT", "B", null, null, null, null, null));

        // 11:30–12:30 只有一小时空档，午饭放进去；不和 12:30 的安排重叠
        assertThat(TripEditor.slot(day, "FOOD")).containsExactly("11:30", "12:30");

        List<Itinerary.Item> full = List.of(
                new Itinerary.Item("d1-1", "09:00", "15:00", "SPOT", "A", null, null, null, null, null));
        // 中午没空，放晚饭
        assertThat(TripEditor.slot(full, "FOOD")).containsExactly("17:00", "18:00");
    }

    @Test
    void roundTripsThroughJsonWithAllFields() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Itinerary it = TripTools.toItinerary(List.of(new TripTools.PlanDay("2026-03-10", "主题",
                List.of(item("09:00", "10:00", "SPOT", "A")))), "注意").withStays(List.of(stay("s1", "酒店", "2026-03-10", "2026-03-11")));

        Itinerary back = mapper.readValue(mapper.writeValueAsString(it), Itinerary.class);

        assertThat(back).isEqualTo(it);
    }

    @Test
    void onlyAmapHttpsPhotosAndKnownTypesAreKept() {
        assertThat(TripEditor.safePhoto("https://store.is.autonavi.com/showpic/abc")).isNotNull();
        assertThat(TripEditor.safePhoto("http://store.is.autonavi.com/showpic/abc")).isNull();
        assertThat(TripEditor.safePhoto("https://169.254.169.254/latest/meta-data")).isNull();
        assertThat(TripEditor.safePhoto("https://evil.com/x.autonavi.com")).isNull();
        assertThat(TripEditor.safePhoto("https://autonavi.com.evil.com/a")).isNull();

        TripEditor.PlacePick p = TripEditor.clean(new TripEditor.PlacePick(" 店 ", null, null, null, null,
                "http://127.0.0.1/a.png", null, null, "<img src=x>", null));
        assertThat(p.name()).isEqualTo("店");
        assertThat(p.photo()).isNull();
        assertThat(p.type()).isNull();
        assertThat(TripTools.webUrl("javascript:alert(1)")).isNull();
        assertThat(TripTools.webUrl("https://www.dianping.com/shop/1")).isEqualTo("https://www.dianping.com/shop/1");
    }

    @Test
    void bookedMarksFollowNamesWhenTheModelRenumbers() {
        Itinerary before = TripTools.toItinerary(List.of(new TripTools.PlanDay("2026-03-10", null, List.of(
                item("09:00", "10:00", "SPOT", "A"), item("12:00", "13:00", "FOOD", "饭店")))), null)
                .withStays(List.of(stay("s1", "酒店", "2026-03-10", "2026-03-11"))).withBooked(List.of("d1-2", "s1"));
        // 新版本前面插了一项，饭店的 id 从 d1-2 变成 d1-3
        Itinerary after = TripTools.toItinerary(List.of(new TripTools.PlanDay("2026-03-10", null, List.of(
                item("08:00", "09:00", "SPOT", "B"), item("09:00", "10:00", "SPOT", "A"),
                item("12:00", "13:00", "FOOD", "饭店")))), null)
                .withStays(List.of(stay("s1", "酒店", "2026-03-10", "2026-03-11")));

        Itinerary carried = TripTools.carryBooked(after, before);

        assertThat(carried.booked()).containsExactlyInAnyOrder("d1-3", "s1");
        assertThat(carried.toMarkdown()).contains("**饭店** · 已预约").contains("以预订页为准） · 已预约");
    }

    private static Itinerary.Stay stay(String id, String name, String in, String out) {
        return new Itinerary.Stay(id, name, in, out, null, 300, "携程", "https://x", null, null, null, null, null,
                null, null, null);
    }

    private static TripTools.PlanItem item(String start, String end, String type, String name) {
        return new TripTools.PlanItem(start, end, type, name, null, null, null, null);
    }
}
