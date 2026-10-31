package com.lifeassistant.core.card;

import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡片中转：编排器每轮对话开一个通道，工具查到结构化结果时往当前会话的通道里发卡片，编排器把它合进 SSE 流。
 * 工具不用知道会话 id：工具执行时 CallRecorder 的 scope 里有。没有开通道（单元测试、评测）时发了也没事。
 */
@Component
public class CardHub {

    private final Map<String, Turn> turns = new ConcurrentHashMap<>();

    /** 一轮对话的卡片通道。 */
    public final class Turn {
        private final String conversationId;
        private final Sinks.Many<Card> sink = Sinks.many().unicast().onBackpressureBuffer();

        private Turn(String conversationId) {
            this.conversationId = conversationId;
        }

        /** 这一轮发出的卡片，关闭通道后结束。 */
        public Flux<Card> cards() {
            return sink.asFlux();
        }

        /** 结束这一轮：卡片流完成，通道注销。 */
        public void close() {
            turns.remove(conversationId, this);
            synchronized (this) {
                sink.tryEmitComplete();
            }
        }

        // 多个工具并行执行时会同时发，Sinks 不允许并发写，这里串行化
        private synchronized void emit(Card card) {
            sink.tryEmitNext(card);
        }
    }

    /** 为会话开一个通道；同一会话的旧通道（理论上不会有）被替换。 */
    public Turn open(String conversationId) {
        Turn turn = new Turn(conversationId);
        turns.put(conversationId, turn);
        return turn;
    }

    /** 告诉模型卡片已经展示了，放进工具返回值里，模型就不再重复列表格。 */
    public static final String SHOWN_HINT = "结果已作为卡片展示给用户（含价格、链接），不要再列表格或重复链接，只用一两句话点评、给出首选";

    /**
     * 往当前工具调用所属会话发一张卡片，返回是否发出去了；没发出去（没开通道）时模型要自己用文字列出结果。
     */
    public boolean emit(String kind, Object data, String summary) {
        CallRecorder.Scope scope = CallRecorder.current();
        Turn turn = scope == null || scope.conversationId() == null ? null : turns.get(scope.conversationId());
        if (turn == null) {
            return false;
        }
        turn.emit(new Card(kind, data, summary, null));
        return true;
    }

    /** 查询时间（ISO 时刻），前端按用户所在时区显示成"10:32 查询"。 */
    public static String now() {
        return Instant.now().toString();
    }
}
