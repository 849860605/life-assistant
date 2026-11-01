package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 路线优化：来回折返的顺序会被理顺，锁定、已预约、吃饭的项位置和时间都不动。 */
class RouteOptimizerTest {

    // 一条东西向的线上，每隔约 5 公里一个点：A(0) B(1) C(2) D(3)
    private static Itinerary.Item spot(String id, double x, String start, String end) {
        return new Itinerary.Item(id, start, end, "SPOT", id, null, null, null, null, null, null,
                108.90 + x * 0.055, 34.25, null, null);
    }

    @Test
    void zigzagIsStraightenedAndFixedItemsStay() {
        Itinerary.Item lunch = new Itinerary.Item("lunch", "12:00", "13:00", "FOOD", "午饭", null, null, null, null,
                null, null, 108.90 + 1.5 * 0.055, 34.25, null, null);
        List<Itinerary.Item> items = List.of(
                spot("A", 0, "09:00", "10:00"),
                spot("C", 2, "10:30", "11:30"),
                lunch,
                spot("B", 1, "13:30", "14:30"),
                spot("D", 3, "15:30", "17:00"));
        RouteOptimizer.Result r = RouteOptimizer.optimize(items, Set.of(), null, null, RouteOptimizer.ESTIMATE);

        assertThat(r.changed()).isTrue();
        assertThat(r.items().get(2).id()).isEqualTo("lunch");
        assertThat(r.items().stream().map(Itinerary.Item::id).toList()).containsExactly("A", "B", "lunch", "C", "D");

        List<Itinerary.Item> timed = RouteOptimizer.retime(r.items(), Set.of(), RouteOptimizer::estimate, 9 * 60);
        assertThat(timed.get(0).start()).isEqualTo("09:00");
        assertThat(timed.get(2).start()).isEqualTo("12:00");
        // B 原来游玩 1 小时，挪到上午后时长不变
        Itinerary.Item b = timed.get(1);
        assertThat(RouteOptimizer.duration(b)).isEqualTo(60);
    }

    @Test
    void lockedAndBookedItemsDoNotMove() {
        Itinerary.Item c = new Itinerary.Item("C", "10:30", "11:30", "SPOT", "C", null, null, null, null, true,
                null, 108.90 + 2 * 0.055, 34.25, null, null);
        List<Itinerary.Item> items = List.of(spot("A", 0, "09:00", "10:00"), c, spot("B", 1, "13:00", "14:00"),
                spot("D", 3, "15:00", "16:00"));
        RouteOptimizer.Result r = RouteOptimizer.optimize(items, Set.of("D"), null, null, RouteOptimizer.ESTIMATE);

        // C 锁定、D 已预约，只剩 A、B 能换；换了也不会更顺，所以不建议改
        assertThat(r.items().get(1).id()).isEqualTo("C");
        assertThat(r.items().get(3).id()).isEqualTo("D");
    }
}
