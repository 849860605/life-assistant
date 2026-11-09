package com.lifeassistant.core.orchestrator;

import com.lifeassistant.common.error.BizException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnDeadlineTest {

    @Test
    void finishedTurnPassesThrough() {
        assertThat(ChatOrchestrator.withDeadline(Flux.just("a", "b"), Duration.ofSeconds(5)).collectList().block())
                .containsExactly("a", "b");
    }

    @Test
    void stuckTurnIsCancelledAndEndsWithError() {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> seen = new ArrayList<>();
        Flux<String> stuck = Flux.concat(Flux.just("a"), Flux.<String>never()).doOnCancel(() -> cancelled.set(true));
        assertThatThrownBy(() -> ChatOrchestrator.withDeadline(stuck, Duration.ofMillis(200))
                .doOnNext(seen::add).blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class).hasMessageContaining("时间太长");
        assertThat(seen).containsExactly("a");
        assertThat(cancelled).isTrue();
    }

    @Test
    void turnLimitFitsUnderNginxTimeout() {
        // nginx 的 proxy_read_timeout 是 600 秒，超时提示要在连接被掐断之前发出去
        assertThat(ChatOrchestrator.TURN_TIMEOUT).isLessThan(Duration.ofSeconds(600));
    }
}
