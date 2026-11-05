package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记下每个会话最近查过的航班（快速查价和完整比价都记）。保存行程时按航班号找回价格、平台和预订链接，
 * 不用经过模型转写。存在 Redis 里（会话一个哈希，字段是"航班号|日期"），重启不丢；Redis 出问题时找不到就只用模型写的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightBook {

    private static final String PREFIX = "flightbook:";
    private static final Duration TTL = Duration.ofDays(3);
    private static final int MAX_PER_CONVERSATION = 60;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 一个查到的航班：哪天、从哪到哪（城市），航班信息，prices 是各平台价格，queriedAt 是查询时间。
     */
    public record Entry(String origin, String destination, String date, FlightTools.Flight flight, String queriedAt) {

        /** 只有一个平台的价格：快速查价查到的，还要后台补比价。 */
        @JsonIgnore
        public boolean quick() {
            return flight.prices() == null || flight.prices().size() <= 1;
        }
    }

    /** 记一批航班；没有会话（页面上直接查）时不记。同一天同一班只留最新的一条（完整比价覆盖快速查价）。 */
    public void record(String conversationId, String origin, String destination, String date,
                       List<FlightTools.Flight> flights, String queriedAt) {
        if (conversationId == null || flights == null || flights.isEmpty()) {
            return;
        }
        String key = PREFIX + conversationId;
        try {
            for (FlightTools.Flight f : flights) {
                redis.opsForHash().put(key, field(f.flightNo(), date),
                        objectMapper.writeValueAsString(new Entry(origin, destination, date, f, queriedAt)));
            }
            redis.expire(key, TTL);
            Long size = redis.opsForHash().size(key);
            if (size != null && size > MAX_PER_CONVERSATION) {
                // 超出上限时删掉最早查的
                List<Entry> all = all(key);
                all.sort(Comparator.comparing(e -> e.queriedAt() == null ? "" : e.queriedAt()));
                for (Entry e : all.subList(0, all.size() - MAX_PER_CONVERSATION)) {
                    redis.opsForHash().delete(key, field(e.flight().flightNo(), e.date()));
                }
            }
        } catch (Exception e) {
            log.warn("记录查过的航班失败：{}", e.getMessage());
        }
    }

    /** 按航班号找这个会话查过的航班，date 不为空时还要日期对上；不限日期时取最新查的。 */
    public Optional<Entry> find(String conversationId, String flightNo, String date) {
        if (conversationId == null || flightNo == null) {
            return Optional.empty();
        }
        String key = PREFIX + conversationId;
        try {
            if (date != null) {
                Object json = redis.opsForHash().get(key, field(flightNo, date));
                return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json.toString(), Entry.class));
            }
            return all(key).stream()
                    .filter(e -> e.flight().flightNo().equalsIgnoreCase(flightNo))
                    .max(Comparator.comparing(e -> e.queriedAt() == null ? "" : e.queriedAt()));
        } catch (Exception e) {
            log.warn("查找查过的航班失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 这个会话记下的全部航班，读不出来的跳过 */
    private List<Entry> all(String key) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<Object, Object> e : redis.opsForHash().entries(key).entrySet()) {
            try {
                out.add(objectMapper.readValue(e.getValue().toString(), Entry.class));
            } catch (Exception ignored) {
                // 格式变了的旧数据不要
            }
        }
        return out;
    }

    private static String field(String flightNo, String date) {
        return flightNo.toUpperCase() + "|" + date;
    }
}
