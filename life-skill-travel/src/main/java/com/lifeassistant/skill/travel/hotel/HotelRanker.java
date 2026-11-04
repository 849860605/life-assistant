package com.lifeassistant.skill.travel.hotel;

import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 候选酒店重排序：先给每家查网上口碑（美团评分按评价条数加权、高德评分），用户提了住宿要求时，
 * 再让小模型按每家的资料（标签、星级、住客评价亮点）打匹配分，最终分 = 口碑 70% + 匹配 30%。
 * 价格不参与排序；平台返回的先后顺序也不参与。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelRanker {

    static final double MATCH_WEIGHT = 0.3;
    /** 口碑分从这个分数起算，4.0 以下都当 0 */
    static final double SCORE_FLOOR = 4.0;
    /** 查口碑整体最多等这么久，没回来的当没有评分 */
    private static final long REVIEW_DEADLINE_SECONDS = 45;

    private static final String PROMPT = """
            你帮用户挑酒店。下面是用户对住宿的要求，和每家候选酒店的资料（星级、平台标签、住客评价亮点）。
            按资料判断每家满足要求的程度，0-10 分：
            - 资料明确满足要求给高分；明确不满足（比如要有窗，标签写"部分客房无窗"）给低分；
            - 资料里没提到的要求按 5 分算，不要猜；
            - 只看要求，不看价格、不看品牌名气。
            reason 用不超过 20 个字说依据，只能引用资料里有的内容。每家都要给，只输出 JSON。""";

    private final HotelReviews reviews;
    private final ChatModelRegistry chatModels;
    private final UsageRecorder usageRecorder;
    private final CallRecorder recorder;

    /** 一家候选：distance 是离搜索地点的米数，tags 是平台标签，star 是星级。 */
    public record Candidate(String name, Integer distance, List<String> tags, String star) {
    }

    /** 排好的一家：口碑、匹配分（没提要求时为空）和匹配依据。 */
    public record Ranked(Candidate hotel, HotelReviews.Review review, Integer match, String matchReason) {
    }

    /** 小模型的输出结构。 */
    public record Matches(List<Match> items) {
    }

    /** 第 index 家（从 0 开始）的匹配分和依据。 */
    public record Match(int index, int score, String reason) {
    }

    /** 查口碑、按需求打分，返回从好到差排好的全部候选。needs 为空时只按口碑排；progress 里报查到第几家了。 */
    public List<Ranked> rank(List<Candidate> candidates, String city, String needs, CardHub.Tracker progress) {
        List<HotelReviews.Review> found = reviewsOf(candidates, city, progress);
        Map<Integer, Match> matches = Map.of();
        if (needs != null && !needs.isBlank()) {
            progress.update("按你的要求（" + needs + "）给 " + candidates.size() + " 家打分");
            matches = match(candidates, found, needs);
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Match m = matches.get(i);
            ranked.add(new Ranked(candidates.get(i), found.get(i), m == null ? null : m.score(),
                    m == null ? null : m.reason()));
        }
        boolean withNeeds = !matches.isEmpty();
        ranked.sort(Comparator.<Ranked>comparingInt(r -> r.review().score() == null ? 1 : 0)
                .thenComparingLong(r -> -Math.round(value(r, withNeeds) * 20))
                .thenComparingInt(r -> r.hotel().distance() == null ? Integer.MAX_VALUE : r.hotel().distance()));
        return ranked;
    }

    /** 排序用的分：口碑分折算到 0-1，提了要求时和匹配分按 7:3 混合。差不到 0.05 的看距离。 */
    static double value(Ranked r, boolean withNeeds) {
        Double score = r.review().score();
        double quality = score == null ? 0 : Math.max(0, Math.min(1, score - SCORE_FLOOR));
        if (!withNeeds) {
            return quality;
        }
        double match = (r.match() == null ? 5 : r.match()) / 10.0;
        return (1 - MATCH_WEIGHT) * quality + MATCH_WEIGHT * match;
    }

    /** 并行查每家的口碑，超时或出错的当没有评分；每查完一家报一次进度。 */
    private List<HotelReviews.Review> reviewsOf(List<Candidate> candidates, String city, CardHub.Tracker progress) {
        HotelReviews.Review none = HotelReviews.combine(null, null, null, null);
        List<HotelReviews.Review> out = new ArrayList<>();
        CallRecorder.Scope scope = CallRecorder.current();
        AtomicInteger done = new AtomicInteger();
        String total = "/" + candidates.size() + "（美团、高德）";
        progress.update("找到 " + candidates.size() + " 家候选，查网上口碑 0" + total);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HotelReviews.Review>> futures = candidates.stream()
                    .map(c -> executor.submit(() -> {
                        try {
                            return recorder.withScope(scope, () -> reviews.of(c.name(), city));
                        } finally {
                            progress.update("找到 " + candidates.size() + " 家候选，查网上口碑 " + done.incrementAndGet() + total);
                        }
                    }))
                    .toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REVIEW_DEADLINE_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(none);
                } catch (Exception e) {
                    log.warn("查 {} 的口碑没成功：{}", candidates.get(i).name(), e.toString());
                    out.add(none);
                }
            }
            executor.shutdownNow();
        }
        return out;
    }

    /** 让小模型按资料给每家打匹配分；失败返回空，排序退回只看口碑。 */
    private Map<Integer, Match> match(List<Candidate> candidates, List<HotelReviews.Review> found, String needs) {
        StringBuilder user = new StringBuilder("用户的住宿要求：").append(needs).append("\n\n候选酒店：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            HotelReviews.Review r = found.get(i);
            user.append(i).append(". ").append(c.name())
                    .append("｜星级 ").append(c.star() == null ? "未知" : c.star())
                    .append("｜标签 ").append(c.tags() == null || c.tags().isEmpty() ? "无" : String.join("、", c.tags()))
                    .append("｜住客评价亮点 ").append(r.highlight() == null ? "无" : r.highlight()).append('\n');
        }
        try {
            ModelSpec model = chatModels.resolve(chatModels.models().getRouter());
            ResponseEntity<ChatResponse, Matches> response = chatModels.client(model).prompt()
                    .system(PROMPT).user(user.toString())
                    .options(chatModels.quickOptions(model, 0.0))
                    .call().responseEntity(Matches.class);
            CallRecorder.Scope scope = CallRecorder.current();
            if (response.response() != null) {
                usageRecorder.record(scope == null ? null : scope.userId(), scope == null ? null : scope.conversationId(),
                        "HOTEL_RANK", model.id(), response.response().getMetadata().getUsage());
            }
            Map<Integer, Match> out = new HashMap<>();
            if (response.entity() != null && response.entity().items() != null) {
                for (Match m : response.entity().items()) {
                    if (m != null && m.index() >= 0 && m.index() < candidates.size()) {
                        out.put(m.index(), new Match(m.index(), Math.max(0, Math.min(10, m.score())), m.reason()));
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("酒店按需求打分失败，只按口碑排：{}", e.getMessage());
            return Map.of();
        }
    }
}
