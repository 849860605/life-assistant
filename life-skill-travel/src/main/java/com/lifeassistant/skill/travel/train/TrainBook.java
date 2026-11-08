package com.lifeassistant.skill.travel.train;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 这个会话查过的车次（Redis，3 天）：保存行程时按交通项名称里的车次号找回票价和购票链接。和 FlightBook 一样。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainBook {

    private static final String PREFIX = "trainbook:";
    private static final Duration TTL = Duration.ofDays(3);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 一个查到的车次：哪天，车次信息。 */
    public record Entry(String date, TrainTools.Train train) {
    }

    /** 记一批车次；没有会话（页面上直接查）时不记。 */
    public void record(String conversationId, String date, List<TrainTools.Train> trains) {
        if (conversationId == null || trains == null || trains.isEmpty()) {
            return;
        }
        String key = PREFIX + conversationId;
        try {
            for (TrainTools.Train t : trains) {
                redis.opsForHash().put(key, t.trainNo() + "|" + date, objectMapper.writeValueAsString(new Entry(date, t)));
            }
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("记录查过的车次失败：{}", e.getMessage());
        }
    }

    /** 按车次号和日期找这个会话查过的车次。 */
    public Optional<Entry> find(String conversationId, String trainNo, String date) {
        if (conversationId == null || trainNo == null || date == null) {
            return Optional.empty();
        }
        try {
            Object json = redis.opsForHash().get(PREFIX + conversationId, trainNo + "|" + date);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json.toString(), Entry.class));
        } catch (Exception e) {
            log.warn("查找查过的车次失败：{}", e.getMessage());
            return Optional.empty();
        }
    }
}
