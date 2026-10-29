package com.lifeassistant.core.weather;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 日出日落和公开的天文数据对比，误差不超过 3 分钟。 */
class SunCalculatorTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    @Test
    void beijingSummerSolstice() {
        // 北京 2024-06-21：日出 04:46，日落 19:46
        SunCalculator.SunTimes sun = SunCalculator.of(LocalDate.of(2024, 6, 21), 39.9042, 116.4074, CHINA).orElseThrow();
        assertClose(sun.sunrise(), LocalTime.of(4, 46));
        assertClose(sun.sunset(), LocalTime.of(19, 46));
    }

    @Test
    void beijingWinterSolstice() {
        // 北京 2024-12-21：日出 07:33，日落 16:53
        SunCalculator.SunTimes sun = SunCalculator.of(LocalDate.of(2024, 12, 21), 39.9042, 116.4074, CHINA).orElseThrow();
        assertClose(sun.sunrise(), LocalTime.of(7, 33));
        assertClose(sun.sunset(), LocalTime.of(16, 53));
    }

    @Test
    void polarNightHasNoSunrise() {
        assertThat(SunCalculator.of(LocalDate.of(2024, 12, 21), 78.2, 15.6, ZoneId.of("Europe/Oslo"))).isEmpty();
    }

    private static void assertClose(LocalTime actual, LocalTime expected) {
        assertThat(Duration.between(expected, actual).abs()).as(actual + " vs " + expected)
                .isLessThanOrEqualTo(Duration.ofMinutes(3));
    }
}
