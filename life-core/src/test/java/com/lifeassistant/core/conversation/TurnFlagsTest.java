package com.lifeassistant.core.conversation;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.audit.CallRecorder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void endsTheTurnWhenTheModelKeepsCallingAfterTheLimit() {
        TurnFlags flags = new TurnFlags();
        flags.start("c1", "去东京");
        int i = 0;
        for (; i < TurnFlags.MAX_TOOLS; i++) {
            assertThat(flags.tooMany("c1", "tool" + i)).isFalse();
        }
        for (; i < TurnFlags.MAX_TOOLS + TurnFlags.HARD_STOP_EXTRA; i++) {
            assertThat(flags.tooMany("c1", "tool" + i)).isTrue();
        }
        assertThatThrownBy(() -> flags.tooMany("c1", "more")).isInstanceOf(BizException.class);
    }

    @Test
    void tracksOtherToolsAndCountersOnlyInsideTheTurn() {
        TurnFlags flags = new TurnFlags();
        flags.start("c1", "Ca 1234 怎么样");
        assertThat(flags.bump("search")).isZero();
        assertThat(flags.userInput()).isNull();
        CallRecorder.NOOP.withScope(new CallRecorder.Scope(1L, "c1", null), () -> {
            flags.tooMany("c1", "rememberFact");
            assertThat(flags.otherToolsCalled(Set.of("rememberFact"))).isFalse();
            flags.tooMany("c1", "searchGuides");
            assertThat(flags.otherToolsCalled(Set.of("rememberFact"))).isTrue();
            assertThat(flags.bump("search")).isEqualTo(1);
            assertThat(flags.bump("search")).isEqualTo(2);
            assertThat(flags.userInput()).isEqualTo("Ca 1234 怎么样");
            return null;
        });
    }
}
