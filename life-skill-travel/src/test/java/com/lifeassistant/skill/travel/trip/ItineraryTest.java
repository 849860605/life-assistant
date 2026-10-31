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

    private static TripTools.PlanItem item(String start, String end, String type, String name) {
        return new TripTools.PlanItem(start, end, type, name, null, null, null, null);
    }
}
