package com.lifeassistant.skill.travel.hotel;

import com.lifeassistant.core.cache.QueryCache;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.core.platform.MeituanHtClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一家酒店的网上口碑：美团（大众点评体系）的评分、评价条数和住客评价亮点，加上高德评分。
 * 都来自官方接口，不爬网页；评价变化慢，缓存 7 天。美团没对上这家分店（推荐了别的店）时不用它的数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelReviews {

    /** 先验：没什么评价的酒店往这个分数拉 */
    static final double PRIOR = 4.5;
    /** 先验相当于多少条评价 */
    static final int PRIOR_WEIGHT = 300;
    /** 高德评分没有条数，按这么多条算 */
    static final int AMAP_WEIGHT = 100;
    /** 美团给了评分没给条数时按这么多条算 */
    static final int MEITUAN_DEFAULT_COUNT = 100;
    /** 美团同时查太多会返回 429，最多同时查这么多家 */
    private static final Semaphore MEITUAN_SLOTS = new Semaphore(4);
    /** 429 时按美团给的建议等 2 秒再试，最多重试这么多次 */
    private static final int MEITUAN_RETRIES = 2;

    private static final Pattern SCORE = Pattern.compile("评分[^0-9\\n]{0,10}([1-5](?:\\.\\d)?)");
    private static final Pattern SCORE_LOOSE = Pattern.compile("([1-5]\\.\\d)\\s*分");
    private static final Pattern COUNT = Pattern.compile("(\\d{1,6})\\s*条[^\\n]{0,4}?评价");
    private static final Pattern HIGHLIGHT = Pattern.compile("住客评价亮点[:：]\\s*([^\\n]+)");
    private static final Pattern HIGHLIGHT_LOOSE = Pattern.compile("(住客(?:普遍|大多|都)[^。）)\\n]+)");
    /** 美团接口返回成功、内容却是"忙不过来"这类客服话术，当成限流处理 */
    private static final Pattern BUSY = Pattern.compile("忙不过来|快速处理名单|请稍后再试|请求过于频繁");
    private static final Pattern NOT_FOUND = Pattern.compile("暂未找到|没有找到|未找到|没查到|暂无.{0,6}信息");

    private final MeituanHtClient meituan;
    private final AmapPlaceService places;
    private final QueryCache queryCache;

    /**
     * 口碑：meituan 和 meituanCount 是美团评分和评价条数，highlight 是住客评价亮点，amap 是高德评分；
     * score 是按评价条数加权、往先验拉过的口碑分（都没有时为空），sources 是给人看的出处；
     * partial 表示有来源这次查失败了（比如美团限流），这样的结果不缓存，下次重查。
     */
    public record Review(Double meituan, Integer meituanCount, String highlight, Double amap, Double score,
                         String sources, boolean partial) {

        Review asPartial() {
            return new Review(meituan, meituanCount, highlight, amap, score, sources, true);
        }
    }

    /** 查一家酒店的口碑；两个来源都没拿到时返回没有分数的空口碑，不抛异常。 */
    public Review of(String hotel, String city) {
        return queryCache.get("hotel-review", hotel + "|" + city, Duration.ofDays(7), false, Review.class,
                () -> load(hotel, city), r -> r.score() != null && !r.partial());
    }

    private Review load(String hotel, String city) {
        Double mtScore = null;
        Integer mtCount = null;
        String highlight = null;
        boolean failed = false;
        if (meituan.available()) {
            try {
                Parsed p = parse(askMeituan(hotel, city), hotel);
                if (p != null) {
                    mtScore = p.score();
                    mtCount = p.count();
                    highlight = p.highlight();
                }
            } catch (RuntimeException e) {
                log.warn("美团查 {} 的评价失败：{}", hotel, e.getMessage());
                failed = true;
            }
        }
        Double amap = null;
        try {
            amap = places.find(hotel, city).map(AmapPlaceService.Place::rating).filter(r -> r > 0).orElse(null);
        } catch (RuntimeException e) {
            log.warn("高德查 {} 的评分失败：{}", hotel, e.getMessage());
            failed = true;
        }
        Review review = combine(mtScore, mtCount, highlight, amap);
        return failed ? review.asPartial() : review;
    }

    /** 问美团这家的评价；限制同时查的家数，遇到限流（429 或"忙不过来"的回复）等一会儿再试，还不行就抛异常。 */
    private String askMeituan(String hotel, String city) {
        for (int attempt = 0; ; attempt++) {
            try {
                MEITUAN_SLOTS.acquire();
                try {
                    String answer = meituan.query(city, hotel + " 的评分、评价条数和住客评价", hotel + "评价怎么样");
                    if (answer != null && BUSY.matcher(answer.length() > 200 ? answer.substring(0, 200) : answer).find()) {
                        throw new IllegalStateException("429 美团说忙不过来");
                    }
                    return answer;
                } finally {
                    MEITUAN_SLOTS.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("查询被中断");
            } catch (RuntimeException e) {
                if (attempt >= MEITUAN_RETRIES || e.getMessage() == null || !e.getMessage().contains("429")) {
                    throw e;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 按评价条数加权平均，再和先验混合；都没有评分时 score 为空。 */
    static Review combine(Double mtScore, Integer mtCount, String highlight, Double amap) {
        double sum = PRIOR * PRIOR_WEIGHT;
        int weight = PRIOR_WEIGHT;
        List<String> sources = new ArrayList<>();
        if (mtScore != null) {
            int n = mtCount == null ? MEITUAN_DEFAULT_COUNT : mtCount;
            sum += mtScore * n;
            weight += n;
            sources.add("美团 " + mtScore + (mtCount == null ? "" : "（" + mtCount + " 条评价）"));
        }
        if (amap != null) {
            sum += amap * AMAP_WEIGHT;
            weight += AMAP_WEIGHT;
            sources.add("高德 " + amap);
        }
        Double score = sources.isEmpty() ? null : Math.round(sum / weight * 100) / 100.0;
        return new Review(mtScore, mtCount, highlight, amap, score, sources.isEmpty() ? null : String.join(" · ", sources),
                false);
    }

    /** 美团回答里的评分、条数和亮点。 */
    record Parsed(Double score, Integer count, String highlight) {
    }

    /**
     * 从美团的 Markdown 回答里取评分、条数和亮点；回答说没找到这家，或者开头说的不是这家店（品牌或分店名对不上），返回空。
     */
    static Parsed parse(String markdown, String hotel) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        String text = markdown.replace("**", "").replace('（', '(').replace('）', ')');
        String head = text.length() > 300 ? text.substring(0, 300) : text;
        if (NOT_FOUND.matcher(head).find() || !mentions(head, hotel)) {
            return null;
        }
        Matcher s = SCORE.matcher(text);
        Double score = s.find() ? Double.valueOf(s.group(1)) : null;
        if (score == null) {
            Matcher loose = SCORE_LOOSE.matcher(text);
            score = loose.find() ? Double.valueOf(loose.group(1)) : null;
        }
        if (score == null) {
            return null;
        }
        Matcher c = COUNT.matcher(text);
        Integer count = c.find() ? Integer.valueOf(c.group(1)) : null;
        Matcher h = HIGHLIGHT.matcher(text);
        String highlight = h.find() ? h.group(1) : null;
        if (highlight == null) {
            Matcher loose = HIGHLIGHT_LOOSE.matcher(text);
            highlight = loose.find() ? loose.group(1) : null;
        }
        if (highlight != null) {
            highlight = highlight.trim().replaceFirst("^[-•·]\\s*", "");
            highlight = highlight.length() > 120 ? highlight.substring(0, 120) + "…" : highlight;
        }
        return new Parsed(score, count, highlight);
    }

    /**
     * 回答开头说的是这家：品牌名（括号前的部分，去掉"酒店"和"锦江|"这类集团前缀）要出现，并且
     * 分店名（括号里的，去掉"店"）也出现，或者美团只写了不带分店的名字（如"西安城墙希尔顿酒店"）。
     * 美团写的是同品牌的另一个分店（品牌后面紧跟着别的括号）就不算。
     */
    static boolean mentions(String text, String hotel) {
        String name = hotel.replace('（', '(').replace('）', ')').trim();
        int open = name.indexOf('(');
        if (open < 0) {
            return text.contains(name);
        }
        String brand = name.substring(0, open).replace("酒店", "").trim();
        brand = brand.substring(brand.lastIndexOf('|') + 1).trim();
        int close = name.indexOf(')', open);
        String branch = name.substring(open + 1, close < 0 ? name.length() : close).replaceAll("店$", "").trim();
        if (brand.isEmpty() || !text.contains(brand)) {
            return false;
        }
        if (branch.isEmpty() || text.contains(branch)) {
            return true;
        }
        Matcher m = Pattern.compile(Pattern.quote(brand) + "([^()\\n]{0,8})").matcher(text);
        while (m.find()) {
            int after = m.end();
            if (after >= text.length() || text.charAt(after) != '(') {
                return true;
            }
        }
        return false;
    }
}
