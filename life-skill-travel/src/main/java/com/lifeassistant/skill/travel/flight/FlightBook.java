package com.lifeassistant.skill.travel.flight;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记下每个会话最近查过的航班（快速查价和完整比价都记）。保存行程时按航班号找回价格、平台和预订链接，
 * 不用经过模型转写；只放内存，后端重启后找不到就只用模型写的。
 */
@Component
public class FlightBook {

    private static final int MAX_CONVERSATIONS = 200;
    private static final int MAX_PER_CONVERSATION = 60;

    /**
     * 一个查到的航班：哪天、从哪到哪（城市），航班信息，prices 是各平台价格，queriedAt 是查询时间。
     */
    public record Entry(String origin, String destination, String date, FlightTools.Flight flight, String queriedAt) {

        /** 只有一个平台的价格：快速查价查到的，还要后台补比价。 */
        public boolean quick() {
            return flight.prices() == null || flight.prices().size() <= 1;
        }
    }

    private final Map<String, List<Entry>> byConversation = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<FlightBook.Entry>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /** 记一批航班；没有会话（页面上直接查）时不记。 */
    public void record(String conversationId, String origin, String destination, String date,
                       List<FlightTools.Flight> flights, String queriedAt) {
        if (conversationId == null || flights == null || flights.isEmpty()) {
            return;
        }
        List<Entry> list = byConversation.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (list) {
            for (FlightTools.Flight f : flights) {
                // 同一天同一班只留最新的一条（完整比价覆盖快速查价）
                list.removeIf(e -> e.flight().flightNo().equals(f.flightNo()) && e.date().equals(date));
                list.add(new Entry(origin, destination, date, f, queriedAt));
            }
            while (list.size() > MAX_PER_CONVERSATION) {
                list.remove(0);
            }
        }
    }

    /** 按航班号找这个会话查过的航班，date 不为空时还要日期对上；新的优先。 */
    public Optional<Entry> find(String conversationId, String flightNo, String date) {
        if (conversationId == null || flightNo == null) {
            return Optional.empty();
        }
        List<Entry> list = byConversation.get(conversationId);
        if (list == null) {
            return Optional.empty();
        }
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Entry e = list.get(i);
                if (e.flight().flightNo().equalsIgnoreCase(flightNo) && (date == null || date.equals(e.date()))) {
                    return Optional.of(e);
                }
            }
        }
        return Optional.empty();
    }
}
