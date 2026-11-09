package com.lifeassistant.api.auth;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 防暴力破解：同一账号 15 分钟内输错 5 次、同一 IP 15 分钟内输错 20 次，就暂停登录到这 15 分钟过去。
 * 计数放在 Redis 里，重启不清零；登录成功清掉这个账号的计数。Redis 不可用时不拦，只记日志，免得自己也登不进去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginGuard {

    private static final int MAX_PER_ACCOUNT = 5;
    private static final int MAX_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    /**
     * 包住一次登录：先检查是否被暂停，再执行 login；密码错（UNAUTHORIZED）时记一次失败，成功时清掉账号计数。
     * 账号计数按输入的登录名算（用户名不区分大小写），用于只能用用户名登录的地方（管理端）。
     *
     * @param scope 区分用户端和管理端，如 "user"、"admin"
     */
    public <T> T guard(String scope, String username, String ip, Supplier<T> login) {
        return guard(scope, username, null, ip, login);
    }

    /**
     * 同上，但账号计数按查到的用户 id 算：用户名和邮箱都能登录同一个账号，按输入的字符串计数的话换着输就能绕过上限。
     * userId 为 null（账号不存在）时按输入的登录名计数。
     */
    public <T> T guard(String scope, String login, Long userId, String ip, Supplier<T> action) {
        String account = userId != null ? key(scope, "uid", String.valueOf(userId))
                : key(scope, "acct", login.trim().toLowerCase(Locale.ROOT));
        String address = key(scope, "ip", ip);
        reject(account, MAX_PER_ACCOUNT, "这个账号密码错误次数太多");
        reject(address, MAX_PER_IP, "登录失败次数太多");
        try {
            T result = action.get();
            safely(() -> redis.delete(account));
            return result;
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.UNAUTHORIZED) {
                count(account);
                count(address);
            }
            throw e;
        }
    }

    private void reject(String key, int max, String reason) {
        String value = safely(() -> redis.opsForValue().get(key));
        if (value != null && Integer.parseInt(value) >= max) {
            Long seconds = safely(() -> redis.getExpire(key));
            long minutes = seconds == null || seconds <= 0 ? WINDOW.toMinutes() : (seconds + 59) / 60;
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, reason + "，请 " + minutes + " 分钟后再试");
        }
    }

    /** 计数加一；第一次失败时开始计时，窗口从第一次失败算起。 */
    private void count(String key) {
        safely(() -> {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1) {
                redis.expire(key, WINDOW);
            }
            return n;
        });
    }

    private static String key(String scope, String kind, String id) {
        return "login:fail:" + scope + ":" + kind + ":" + id;
    }

    private static <T> T safely(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.warn("登录防刷计数失败（Redis 不可用？），这次不拦：{}", e.getMessage());
            return null;
        }
    }
}
