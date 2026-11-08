package com.lifeassistant.core.conversation;

import com.lifeassistant.core.audit.CallRecorder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个会话"这一轮"的状态：用户每发一条消息清空一次，工具在这一轮里打标记。现在只用来防止规划行程时查太多：
 * 快速查价时标记 PLANNING，同一轮里完整比价、逐段查路线、搜攻略就直接退回，让模型先把方案写出来。
 * 只放内存，重启丢了也没关系（最多这一轮不拦）。
 */
@Component
public class TurnFlags {

    /** 这一轮在规划完整行程（已经快速查过价） */
    public static final String PLANNING = "PLANNING";
    /** 一轮最长算多久，超过就当新的一轮（防止标记一直留着） */
    private static final long TURN_MS = 10 * 60 * 1000;

    private final Map<String, Turn> turns = new ConcurrentHashMap<>();

    /** 一轮里同一个工具最多调几次、所有工具加起来最多几次，防止模型在几个工具之间来回调停不下来 */
    public static final int MAX_SAME_TOOL = 6;
    public static final int MAX_TOOLS = 30;

    private record Turn(String input, Set<String> flags, long startedAt, Map<String, Integer> calls) {
    }

    /** 用户发了一条新消息：开始新的一轮，记下这句话。 */
    public void start(String conversationId, String input) {
        long now = System.currentTimeMillis();
        turns.values().removeIf(t -> now - t.startedAt() > TURN_MS);
        turns.put(conversationId, new Turn(input == null ? "" : input, ConcurrentHashMap.newKeySet(), now,
                new ConcurrentHashMap<>()));
    }

    /** 在当前工具调用所属会话的这一轮打个标记。 */
    public void mark(String flag) {
        Turn t = current();
        if (t != null) {
            t.flags().add(flag);
        }
    }

    /** 当前工具调用所属会话的这一轮有没有这个标记。 */
    public boolean has(String flag) {
        Turn t = current();
        return t != null && t.flags().contains(flag);
    }

    /**
     * 这一轮在规划、而用户这句话里没说要比价时返回 true：完整比价、逐段查路线、搜攻略这些慢工具该退回。
     * 用户明确说"比价""比好价"时不拦。
     */
    public boolean planningOnly() {
        Turn t = current();
        if (t == null || !t.flags().contains(PLANNING)) {
            return false;
        }
        return !(t.input().contains("比价") || t.input().contains("比好价") || t.input().contains("比一下价"));
    }

    /**
     * 记一次工具调用，返回这次该不该拦下：同一个工具这一轮已经调了 MAX_SAME_TOOL 次，或所有工具加起来到了 MAX_TOOLS 次。
     * 不在会话里调用的（没有这一轮的记录）不拦。
     */
    public boolean tooMany(String conversationId, String tool) {
        Turn t = conversationId == null ? null : turns.get(conversationId);
        if (t == null) {
            return false;
        }
        int same = t.calls().merge(tool, 1, Integer::sum);
        int total = t.calls().values().stream().mapToInt(Integer::intValue).sum();
        return same > MAX_SAME_TOOL || total > MAX_TOOLS;
    }

    /** 这一轮里第一次走到这里返回 true（之后都返回 false），用来"同一件事一轮只扣一次积分"；不在会话里调用时返回 false。 */
    public boolean once(String key) {
        Turn t = current();
        return t != null && t.flags().add("once:" + key);
    }

    /** 这一轮里 once(key) 是不是已经走过。 */
    public boolean done(String key) {
        Turn t = current();
        return t != null && t.flags().contains("once:" + key);
    }

    /** 现在是不是在某个会话的一轮对话里（工具调用期间）。 */
    public boolean inTurn() {
        return current() != null;
    }

    /** 用户这一轮说的话里有没有其中某个词。 */
    public boolean userSaid(String... words) {
        Turn t = current();
        if (t == null) {
            return false;
        }
        for (String w : words) {
            if (t.input().contains(w)) {
                return true;
            }
        }
        return false;
    }

    private Turn current() {
        CallRecorder.Scope scope = CallRecorder.current();
        if (scope == null || scope.conversationId() == null) {
            return null;
        }
        Turn t = turns.get(scope.conversationId());
        return t != null && System.currentTimeMillis() - t.startedAt() <= TURN_MS ? t : null;
    }
}
