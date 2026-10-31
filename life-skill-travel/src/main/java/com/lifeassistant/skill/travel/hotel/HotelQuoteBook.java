package com.lifeassistant.skill.travel.hotel;

import com.lifeassistant.core.place.AmapPlaceService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记下每个会话最近几次酒店比价的结果。保存行程时按酒店名找回各平台报价和其他候选，
 * 价格和链接就不用经过模型转写。只放内存，重启后找不到时行程里只有酒店名，用户可以在页面上重新比价。
 */
@Component
public class HotelQuoteBook {

    private static final int MAX_CONVERSATIONS = 200;
    private static final int MAX_PER_CONVERSATION = 5;

    /** 一次比价：城市、日期、查询时间，以及每家酒店的各平台报价（按比价时的顺序）。 */
    public record Comparison(String city, String checkIn, String checkOut, String queriedAt, List<Entry> hotels) {
    }

    public record Entry(String hotel, List<HotelPriceSource.Quote> quotes) {
    }

    /** 找到的那家酒店和它所在的那次比价。 */
    public record Found(Comparison comparison, Entry entry) {
    }

    private final Map<String, List<Comparison>> byConversation = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Comparison>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /** 记一次比价；没有会话（比如页面上直接查）时不记。 */
    public void record(String conversationId, Comparison comparison) {
        if (conversationId == null) {
            return;
        }
        List<Comparison> list = byConversation.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(comparison);
            if (list.size() > MAX_PER_CONVERSATION) {
                list.remove(0);
            }
        }
    }

    /** 在这个会话最近的比价里按名称找酒店，新的优先。 */
    public Optional<Found> find(String conversationId, String hotel) {
        List<Comparison> list = conversationId == null ? null : byConversation.get(conversationId);
        if (list == null || hotel == null) {
            return Optional.empty();
        }
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Comparison c = list.get(i);
                for (Entry e : c.hotels()) {
                    if (AmapPlaceService.similarName(hotel, e.hotel())) {
                        return Optional.of(new Found(c, e));
                    }
                }
            }
        }
        return Optional.empty();
    }
}
