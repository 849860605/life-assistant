package com.lifeassistant.core.limit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 通用的 Redis 计数限流（固定窗口）：每次调用先加一，超过上限返回 false。
 * 按分钟的窗口用"当前第几分钟"做 key，按天的窗口用北京时间的日期做 key，过了这个窗口自然换新 key。
 * Redis 出问题时放行，只记日志，不因为计数挡住正常用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String PREFIX = "limit:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate redis;

    /** 每分钟最多 limit 次：这次算进去之后没超过返回 true。 */
    public boolean perMinute(String name, int limit) {
        long minute = System.currentTimeMillis() / 60_000;
        return hit(PREFIX + name + ":m" + minute, limit, Duration.ofMinutes(2));
    }

    /** 每天（北京时间自然日）最多 limit 次：这次算进去之后没超过返回 true。 */
    public boolean perDay(String name, int limit) {
        return hit(PREFIX + name + ":d" + LocalDate.now(ZONE), limit, Duration.ofHours(25));
    }

    /** 今天这一项已经用了几次（不加一）；Redis 出问题时返回 0。 */
    public int usedToday(String name) {
        try {
            String value = redis.opsForValue().get(PREFIX + name + ":d" + LocalDate.now(ZONE));
            return value == null ? 0 : Integer.parseInt(value);
        } catch (RuntimeException e) {
            log.warn("读限流计数失败：{}", e.getMessage());
            return 0;
        }
    }

    /** 计数加一，第一次时设过期；超过上限返回 false。 */
    private boolean hit(String key, int limit, Duration ttl) {
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1) {
                redis.expire(key, ttl);
            }
            return n == null || n <= limit;
        } catch (RuntimeException e) {
            log.warn("限流计数失败（Redis 不可用？），这次放行：{}", e.getMessage());
            return true;
        }
    }
}
