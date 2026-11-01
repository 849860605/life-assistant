package com.lifeassistant.core.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 比价、景点、规则这类外部查询的缓存，存在 Redis 里（重启不丢）。按查询内容区分，不按用户区分（价格对谁都一样）。
 * refresh 为 true 时跳过缓存重新查（页面上的"刷新"）；keep 判断结果值不值得缓存（空的、失败的不缓存）。
 * Redis 出问题时直接查，不影响功能。结果自己带查询时间字段的，命中缓存时就是当初查的时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryCache {

    private static final String PREFIX = "qc:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public <T> T get(String namespace, String key, Duration ttl, boolean refresh, Class<T> type, Supplier<T> load,
                     Predicate<T> keep) {
        return get(namespace, key, ttl, refresh, objectMapper.constructType(type), load, keep);
    }

    public <T> T get(String namespace, String key, Duration ttl, boolean refresh, JavaType type, Supplier<T> load,
                     Predicate<T> keep) {
        String redisKey = PREFIX + namespace + ":" + hash(key);
        if (!refresh) {
            try {
                String json = redis.opsForValue().get(redisKey);
                if (json != null) {
                    return objectMapper.readValue(json, type);
                }
            } catch (Exception e) {
                log.warn("读查询缓存 {} 失败：{}", namespace, e.getMessage());
            }
        }
        T value = load.get();
        if (value != null && keep.test(value)) {
            try {
                redis.opsForValue().set(redisKey, objectMapper.writeValueAsString(value), ttl);
            } catch (Exception e) {
                log.warn("写查询缓存 {} 失败：{}", namespace, e.getMessage());
            }
        }
        return value;
    }

    public JavaType listOf(Class<?> element) {
        return objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }

    private static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
