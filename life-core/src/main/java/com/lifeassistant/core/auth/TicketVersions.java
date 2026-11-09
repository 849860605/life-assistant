package com.lifeassistant.core.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * 每个用户的下载票据版本，存在 Redis 里（没有记录就是 0）。改密码、重置密码、停用账号时加一，
 * 之前签的下载票据（里面带着旧版本）就不再认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketVersions {

    private static final String PREFIX = "media-ticket:ver:";

    private final StringRedisTemplate redis;

    /** 现在的版本；Redis 读不到时返回空，由调用方决定放不放行。 */
    public OptionalLong current(long userId) {
        try {
            String value = redis.opsForValue().get(PREFIX + userId);
            return OptionalLong.of(value == null ? 0 : Long.parseLong(value));
        } catch (RuntimeException e) {
            log.warn("读下载票据版本失败：{}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    /** 让这个用户之前签的票据全部作废。 */
    public void bump(long userId) {
        try {
            redis.opsForValue().increment(PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("作废用户 {} 的下载票据失败（Redis 不可用？）：{}", userId, e.getMessage());
        }
    }
}
