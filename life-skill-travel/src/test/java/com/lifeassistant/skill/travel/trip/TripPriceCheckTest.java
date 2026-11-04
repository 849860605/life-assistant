package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.skill.travel.flight.FlightTools;
import com.lifeassistant.skill.travel.hotel.HotelPriceSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 后台补比价写回：酒店各平台报价合并取最低，航班价格改成各平台最低并列出比价，写完去掉"比价中"。 */
class TripPriceCheckTest {

    private static Itinerary trip() {
        Itinerary.Item flight = new Itinerary.Item("d1-1", "08:00", "10:30", "TRANSPORT", "东航 MU5402 双流T2 → 萧山T3",
                null, "飞猪 ¥680，价格以平台为准", 680, "https://fliggy/1", null);
        Itinerary.Item spot = new Itinerary.Item("d1-2", "14:00", "17:00", "SPOT", "西湖", null, null, null, null, null);
        Itinerary.Stay stay = new Itinerary.Stay("s1", "杭州西湖酒店", "2026-12-12", "2026-12-14", null, 420, "RollingGo",
                "https://rg/1", "2026-12-01T00:00:00Z",
                List.of(new Itinerary.StayQuote("RollingGo", 420, "https://rg/1", null, null)),
                null, null, null, null, null, null);
        return new Itinerary(List.of(new Itinerary.Day("2026-12-12", null, List.of(flight, spot))), null)
                .withStays(List.of(stay))
                .withChecking(Map.of("杭州西湖酒店|2026-12-12", "t", "MU5402|2026-12-12", "t", "other|x", "t"));
    }

    @Test
    void mergesHotelQuotesAndKeepsCheapest() {
        Itinerary out = TripPriceCheck.apply(trip(), Map.of("杭州西湖酒店|2026-12-12", List.of(
                        new HotelPriceSource.Quote("携程", "OK", 388, "参考价", "https://ctrip/1", "杭州西湖酒店", List.of(), null),
                        new HotelPriceSource.Quote("美团", "FAILED", null, null, null, null, List.of(), "超时"))),
                Map.of(), Set.of("杭州西湖酒店|2026-12-12"));
        Itinerary.Stay st = out.stays().get(0);
        assertThat(st.price()).isEqualTo(388);
        assertThat(st.platform()).isEqualTo("携程");
        assertThat(st.url()).isEqualTo("https://ctrip/1");
        assertThat(st.quotes()).extracting(Itinerary.StayQuote::platform).containsExactly("RollingGo", "携程");
        assertThat(out.checking()).containsOnlyKeys("MU5402|2026-12-12", "other|x");
    }

    @Test
    void flightTakesLowestAcrossPlatforms() {
        Itinerary out = TripPriceCheck.apply(trip(), Map.of(), Map.of("MU5402|2026-12-12", Map.of(
                "携程", new FlightTools.PlatformPrice(650, "https://ctrip/f", null),
                "美团", new FlightTools.PlatformPrice(700, null, null))), Set.of("MU5402|2026-12-12"));
        Itinerary.Item f = out.days().get(0).items().get(0);
        assertThat(f.cost()).isEqualTo(650);
        assertThat(f.url()).isEqualTo("https://ctrip/f");
        assertThat(f.note()).startsWith("携程 ¥650，比价：").contains("飞猪 ¥680").contains("美团 ¥700")
                .endsWith("价格以平台为准");
        assertThat(out.days().get(0).items().get(1)).isEqualTo(trip().days().get(0).items().get(1));
        assertThat(out.checking()).containsOnlyKeys("杭州西湖酒店|2026-12-12", "other|x");
    }

    @Test
    void nothingFoundStillClearsChecking() {
        Itinerary out = TripPriceCheck.apply(trip(), Map.of(), Map.of(),
                Set.of("杭州西湖酒店|2026-12-12", "MU5402|2026-12-12", "other|x"));
        assertThat(out.checking()).isNull();
        assertThat(out.stays().get(0).price()).isEqualTo(420);
        assertThat(out.days().get(0).items().get(0).cost()).isEqualTo(680);
    }
}
