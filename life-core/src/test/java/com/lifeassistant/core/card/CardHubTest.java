package com.lifeassistant.core.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardHubTest {

    @Test
    void interjectionsAreCappedPerTurnAndCut() {
        CardHub hub = new CardHub();
        // 没有正在进行的回答
        assertThat(hub.interject("c1", "1", "补充")).isFalse();

        CardHub.Turn turn = hub.open("c1");
        for (int i = 0; i < CardHub.MAX_INTERJECTIONS; i++) {
            assertThat(hub.interject("c1", String.valueOf(i), "x".repeat(800))).isTrue();
        }
        // 第 4 句不收，等这轮结束后前端正常发
        assertThat(hub.interject("c1", "9", "再补一句")).isFalse();
        // 取走了也不能再塞
        assertThat(hub.takeInterjections("c1")).hasSize(CardHub.MAX_INTERJECTIONS)
                .allSatisfy(t -> assertThat(t).hasSize(CardHub.MAX_INTERJECTION_LENGTH));
        assertThat(hub.interject("c1", "10", "还有")).isFalse();

        // 新的一轮重新计数
        turn.close();
        hub.open("c1");
        assertThat(hub.interject("c1", "11", "新一轮")).isTrue();
    }
}
