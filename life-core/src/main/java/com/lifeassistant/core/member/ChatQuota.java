package com.lifeassistant.core.member;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 每天和星络对话的次数：免费用户和会员上限不同（后台可改），按北京时间自然日计，存在 Redis 里。
 * 每发一条消息算一次（回答过程中的补充不算）；Redis 出问题时放行，不因为计数挡住用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatQuota {

    private static final String PREFIX = "quota:chat:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate redis;
    private final MembershipService membership;

    /** 今天的用量：used 已用次数，limit 今天的上限，member 是不是会员。 */
    public record Usage(int used, int limit, boolean member) {
    }

    /** 查今天用了多少。 */
    public Usage usage(long userId) {
        boolean member = membership.status(userId).active();
        int limit = limitFor(member);
        int used = 0;
        try {
            String value = redis.opsForValue().get(key(userId));
            used = value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            log.warn("读对话次数失败：{}", e.getMessage());
        }
        return new Usage(used, limit, member);
    }

    /** 发消息前调用：先记一次，超过上限就退回这一次并报错，提示去开通会员或明天再来。 */
    public void consume(long userId) {
        boolean member = membership.status(userId).active();
        int limit = limitFor(member);
        Long used;
        try {
            used = redis.opsForValue().increment(key(userId));
            if (used != null && used == 1) {
                redis.expire(key(userId), Duration.ofDays(2));
            }
        } catch (Exception e) {
            log.warn("记对话次数失败，这次放行：{}", e.getMessage());
            return;
        }
        if (used != null && used > limit) {
            try {
                redis.opsForValue().decrement(key(userId));
            } catch (Exception ignored) {
                // 退回失败只是多算一次，明天自然清零
            }
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, member
                    ? "今天的 " + limit + " 次对话用完了，明天再来吧"
                    : "今天的 " + limit + " 次免费对话用完了，开通会员每天可以用 " + membership.quota().memberDaily() + " 次");
        }
    }

    private int limitFor(boolean member) {
        MembershipService.Quota quota = membership.quota();
        return member ? quota.memberDaily() : quota.freeDaily();
    }

    private static String key(long userId) {
        return PREFIX + userId + ":" + LocalDate.now(ZONE);
    }
}
