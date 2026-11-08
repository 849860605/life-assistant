package com.lifeassistant.core.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 一轮里同一个工具调太多次要拦下；新的一轮重新计数。 */
class TurnFlagsTest {

    @Test
    void limitsRepeatedToolCallsPerTurn() {
        TurnFlags flags = new TurnFlags();
        flags.start("c1", "去东京");
        for (int i = 0; i < TurnFlags.MAX_SAME_TOOL; i++) {
            assertThat(flags.tooMany("c1", "quickFlights")).isFalse();
        }
        assertThat(flags.tooMany("c1", "quickFlights")).isTrue();
        assertThat(flags.tooMany("c1", "dailyWeather")).isFalse();
        flags.start("c1", "换个日期");
        assertThat(flags.tooMany("c1", "quickFlights")).isFalse();
        assertThat(flags.tooMany("no-turn", "quickFlights")).isFalse();
    }
}
