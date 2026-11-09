package com.lifeassistant.skill.travel.flight;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 要扣积分的航班动态查询，航班号得是用户这句话里说的。 */
class FlightStatusToolsTest {

    @Test
    void flightNumberMustAppearInUserText() {
        assertThat(FlightStatusTools.mentioned("CZ3101 现在什么情况", "cz3101")).isTrue();
        assertThat(FlightStatusTools.mentioned("帮我看下 ca 1234 延误没", "CA1234")).isTrue();
        assertThat(FlightStatusTools.mentioned("坐CA1234去北京", " CA 1234 ")).isTrue();
        assertThat(FlightStatusTools.mentioned("CA12345", "CA1234")).isFalse();
        assertThat(FlightStatusTools.mentioned("XCA1234", "CA1234")).isFalse();
        assertThat(FlightStatusTools.mentioned("帮我查下这个航班", "CA1234")).isFalse();
        assertThat(FlightStatusTools.mentioned(null, "CA1234")).isFalse();
        assertThat(FlightStatusTools.mentioned("随便", "")).isFalse();
    }
}
