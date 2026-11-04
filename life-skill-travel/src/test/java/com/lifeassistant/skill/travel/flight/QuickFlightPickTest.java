package com.lifeassistant.skill.travel.flight;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 快速查价按偏好挑航班：要直飞就去掉中转，早班、晚班按起飞时间筛，其余按价格。 */
class QuickFlightPickTest {

    private static FlightTools.Flight flight(String no, String dep, int price, boolean transfer) {
        return new FlightTools.Flight(no, "东航", dep, "12:00", "双流", "萧山", null,
                Map.of("飞猪", new FlightTools.PlatformPrice(price, null, null)), price, "飞猪", null, null, transfer, false,
                null, null, null);
    }

    private final List<FlightTools.Flight> flights = List.of(flight("MU1", "07:30", 700, false),
            flight("MU2", "19:30", 500, false), flight("MU3/MU4", "09:00", 300, true));

    @Test
    void directOnlyDropsTransfers() {
        assertThat(FlightTools.pick(flights, "直飞")).extracting(FlightTools.Flight::flightNo).containsExactly("MU2", "MU1");
    }

    @Test
    void morningKeepsEarlyFlights() {
        assertThat(FlightTools.pick(flights, "早班")).extracting(FlightTools.Flight::flightNo).containsExactly("MU3/MU4", "MU1");
    }

    @Test
    void noPreferenceSortsByPrice() {
        assertThat(FlightTools.pick(flights, null)).extracting(FlightTools.Flight::flightNo)
                .containsExactly("MU3/MU4", "MU2", "MU1");
    }
}
