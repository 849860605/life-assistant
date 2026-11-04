package com.lifeassistant.core.card;

import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 卡片中转：编排器每轮对话开一个通道，工具查到结构化结果时往当前会话的通道里发卡片（还有长任务的进度），编排器把它合进 SSE 流。
 * 工具不用知道会话 id：工具执行时 CallRecorder 的 scope 里有。没有开通道（单元测试、评测）时发了也没事。
 */
@Component
public class CardHub {

    private final Map<String, Turn> turns = new ConcurrentHashMap<>();

    /** 一轮对话的卡片通道。 */
    public final class Turn {
        private final String conversationId;
        private final Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        /** 回答过程中用户补充的话，等下一次工具调用结束时交给模型 */
        private final Queue<Interjection> inbox = new ConcurrentLinkedQueue<>();

        private Turn(String conversationId) {
            this.conversationId = conversationId;
        }

        /** 这一轮发出的卡片（Card）和进度（Progress），关闭通道后结束。 */
        public Flux<Object> events() {
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
        private synchronized void emit(Object event) {
            sink.tryEmitNext(event);
        }
    }

    /** 为会话开一个通道；同一会话的旧通道（理论上不会有）被替换。 */
    public Turn open(String conversationId) {
        Turn turn = new Turn(conversationId);
        turns.put(conversationId, turn);
        return turn;
    }

    /** 用户在回答过程中补充的一句话；id 是前端给的，用来对上是哪一条。 */
    public record Interjection(String id, String text) {
    }

    /** 回答过程中收到用户补充，放进这一轮的收件箱；这个会话没有正在进行的回答时返回 false，前端改成正常发送。 */
    public boolean interject(String conversationId, String id, String text) {
        Turn turn = turns.get(conversationId);
        if (turn == null) {
            return false;
        }
        turn.inbox.add(new Interjection(id, text));
        return true;
    }

    /**
     * 取走这一轮还没交给模型的补充，每条发一个已完成的进度（id 是 interject:前端 id），前端据此知道星络已经看到；
     * 没有正在进行的回答或没有补充时返回空列表。
     */
    public List<String> takeInterjections(String conversationId) {
        Turn turn = conversationId == null ? null : turns.get(conversationId);
        if (turn == null) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Interjection i; (i = turn.inbox.poll()) != null; ) {
            texts.add(i.text());
            turn.emit(new Progress("interject:" + i.id(), "收到补充", i.text(), true));
        }
        return texts;
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

    /**
     * 给当前工具调用开一条进度，title 是这一步做什么（如"查询酒店并比价"）。要在工具线程里调用（那里有会话）；
     * 拿到的 Tracker 可以在任何线程里更新。没开通道时返回的 Tracker 什么也不做。
     */
    public Tracker track(String title) {
        CallRecorder.Scope scope = CallRecorder.current();
        Turn turn = scope == null || scope.conversationId() == null ? null : turns.get(scope.conversationId());
        return new Tracker(turn, UUID.randomUUID().toString().substring(0, 8), title);
    }

    /** 一条进度的句柄：update 换成新的说明，finish 标记结束；结束后再调不再发。 */
    public static final class Tracker {
        private final Turn turn;
        private final String id;
        private final String title;
        private volatile boolean finished;

        private Tracker(Turn turn, String id, String title) {
            this.turn = turn;
            this.id = id;
            this.title = title;
        }

        /** 不发任何东西的进度，单元测试和不在对话里调用时用。 */
        public static Tracker none() {
            return new Tracker(null, "none", "");
        }

        public void update(String detail) {
            send(detail, false);
        }

        public void finish(String detail) {
            send(detail, true);
        }

        private void send(String detail, boolean done) {
            if (turn == null || finished) {
                return;
            }
            finished = done;
            turn.emit(new Progress(id, title, detail, done));
        }
    }

    /** 查询时间（ISO 时刻），前端按用户所在时区显示成"10:32 查询"。 */
    public static String now() {
        return Instant.now().toString();
    }
}
