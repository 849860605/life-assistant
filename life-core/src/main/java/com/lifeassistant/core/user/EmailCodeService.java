package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.mail.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 邮箱验证码：6 位数字，Redis 里只存哈希，10 分钟有效，一个验证码最多试 5 次，用过就作废。
 * 频控：同一邮箱 60 秒一次、每天 10 次；同一 IP 每天 30 次；全站每天上限（life.mail.daily-limit，默认 300，
 * QQ 邮箱每天能发的量有限）。按用途分开：注册、找回密码、绑定邮箱各用各的验证码。
 */
@Service
public class EmailCodeService {

    /** 验证码用途 */
    public enum Purpose {
        REGISTER("注册星络账号"), RESET("找回星络账号的密码"), BIND("给星络账号绑定邮箱");

        private final String action;

        Purpose(String action) {
            this.action = action;
        }
    }

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 同一邮箱两次发送至少隔这么多秒 */
    public static final long COOLDOWN_SECONDS = 60;
    static final Duration COOLDOWN = Duration.ofSeconds(COOLDOWN_SECONDS);
    static final int MAX_TRIES = 5;
    static final int MAX_PER_EMAIL_DAY = 10;
    static final int MAX_PER_IP_DAY = 30;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "email-code:";

    private final StringRedisTemplate redis;
    private final EmailSender sender;
    private final int dailyLimit;
    private final SecureRandom random = new SecureRandom();

    public EmailCodeService(StringRedisTemplate redis, EmailSender sender,
                            @Value("${life.mail.daily-limit:300}") int dailyLimit) {
        this.redis = redis;
        this.sender = sender;
        this.dailyLimit = dailyLimit;
    }

    /** 能不能发验证码（发信邮箱配好了没有）。 */
    public boolean ready() {
        return sender.ready();
    }

    /**
     * 发验证码。deliver 为 false 时照样走频控、照样回成功，但不真发（找回密码时邮箱没注册，不暴露这个邮箱有没有账号）。
     * 超频控抛 TOO_MANY_REQUESTS，发信失败抛 BAD_REQUEST。
     */
    public void send(String email, Purpose purpose, String ip, boolean deliver) {
        if (!sender.ready()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时不能发邮件，请联系管理员");
        }
        String day = LocalDate.now(ZONE).toString();
        String cool = PREFIX + "cool:" + purpose + ":" + email;
        String emailDay = PREFIX + "day:" + day + ":mail:" + email;
        String ipDay = PREFIX + "day:" + day + ":ip:" + ip;
        String allDay = PREFIX + "day:" + day + ":all";
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cool, "1", COOLDOWN))) {
                Long left = redis.getExpire(cool);
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                        "发送太频繁，请 " + (left == null || left <= 0 ? COOLDOWN.toSeconds() : left) + " 秒后再试");
            }
            String limited = count(emailDay) >= MAX_PER_EMAIL_DAY ? "这个邮箱今天收验证码的次数太多了，明天再试"
                    : count(ipDay) >= MAX_PER_IP_DAY ? "今天获取验证码的次数太多了，明天再试"
                    : count(allDay) >= dailyLimit ? "今天的验证码发送量到上限了，明天再试" : null;
            if (limited != null) {
                redis.delete(cool);
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, limited);
            }
            if (deliver) {
                String code = String.format("%06d", random.nextInt(1_000_000));
                redis.opsForValue().set(codeKey(email, purpose), hash(email, purpose, code), CODE_TTL);
                redis.delete(triesKey(email, purpose));
                try {
                    sender.send(email, "【星络】你的验证码是 " + code, text(purpose, code));
                } catch (RuntimeException e) {
                    redis.delete(codeKey(email, purpose));
                    redis.delete(cool);
                    throw e;
                }
                increment(allDay);
            }
            increment(emailDay);
            increment(ipDay);
        } catch (DataAccessException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时不能发验证码，请稍后再试");
        }
    }

    /** 核对验证码：对了就作废（不能再用第二次）；没有、过期、错了、错太多次都抛 BAD_REQUEST。 */
    public void verify(String email, Purpose purpose, String code) {
        String input = code == null ? "" : code.trim();
        try {
            String saved = redis.opsForValue().get(codeKey(email, purpose));
            if (saved == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "验证码已过期或还没获取，请重新获取");
            }
            Long tries = redis.opsForValue().increment(triesKey(email, purpose));
            if (tries != null && tries == 1) {
                redis.expire(triesKey(email, purpose), CODE_TTL);
            }
            if (tries != null && tries > MAX_TRIES) {
                redis.delete(codeKey(email, purpose));
                redis.delete(triesKey(email, purpose));
                throw new BizException(ErrorCode.BAD_REQUEST, "验证码输错次数太多，请重新获取");
            }
            if (!MessageDigest.isEqual(saved.getBytes(StandardCharsets.UTF_8),
                    hash(email, purpose, input).getBytes(StandardCharsets.UTF_8))) {
                long left = tries == null ? 0 : MAX_TRIES - tries;
                throw new BizException(ErrorCode.BAD_REQUEST,
                        left > 0 ? "验证码不对，还能再试 " + left + " 次" : "验证码不对，请重新获取");
            }
            redis.delete(codeKey(email, purpose));
            redis.delete(triesKey(email, purpose));
        } catch (DataAccessException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时不能校验验证码，请稍后再试");
        }
    }

    /** 邮件正文：验证码、有效期、不是本人操作就忽略。 */
    static String text(Purpose purpose, String code) {
        return "你正在" + purpose.action + "，验证码是：" + code + "\n\n"
                + CODE_TTL.toMinutes() + " 分钟内有效，请不要告诉别人。\n"
                + "如果不是你本人操作，忽略这封邮件就行，你的账号不会有任何变化。\n\n—— 星络";
    }

    private int count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** 当天计数加一，第一次时设 25 小时过期（key 里带日期，过了这天自然不再用）。 */
    private void increment(String key) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, Duration.ofHours(25));
        }
    }

    private static String codeKey(String email, Purpose purpose) {
        return PREFIX + "code:" + purpose + ":" + email;
    }

    private static String triesKey(String email, Purpose purpose) {
        return PREFIX + "tries:" + purpose + ":" + email;
    }

    /** 验证码的哈希，和邮箱、用途绑在一起，换个用途或邮箱用不了。 */
    private static String hash(String email, Purpose purpose, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((purpose + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
