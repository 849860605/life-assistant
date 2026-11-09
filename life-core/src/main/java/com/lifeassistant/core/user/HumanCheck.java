package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 要验证码前的"真人检查"（不弹验证码图片）：前端先领一道题，在浏览器里算出答案（工作量证明，见 ProofOfWork）再来要验证码。
 * 题目 = base64url(16 字节随机数 + 过期秒 + 难度) + "." + HMAC-SHA256 签名，5 分钟有效，一道题只能用一次（Redis 里记下用过的）。
 * 签名密钥由 LIFE_CRYPTO_KEY 派生；没配时每次启动随机生成（重启后没用的题作废，刷新页面重领就行）。
 */
@Slf4j
@Component
public class HumanCheck {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final String USED_PREFIX = "pow:used:";
    private static final int PAYLOAD_BYTES = 16 + 8 + 1;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final int difficulty;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public HumanCheck(LifeProperties properties, StringRedisTemplate redis,
                      @Value("${life.auth.pow-difficulty:16}") int difficulty) {
        this.redis = redis;
        this.difficulty = Math.max(1, Math.min(difficulty, 32));
        String key = properties.getCrypto().getKey();
        byte[] seed;
        if (StringUtils.hasText(key)) {
            seed = ("pow-challenge|" + key).getBytes(StandardCharsets.UTF_8);
        } else {
            seed = new byte[32];
            random.nextBytes(seed);
        }
        try {
            this.secret = MessageDigest.getInstance("SHA-256").digest(seed);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一道题：token 是题目，difficulty 是要求开头有几个 0 比特。 */
    public record Challenge(String token, int difficulty) {
    }

    /** 出一道新题。 */
    public Challenge issue() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                .put(salt).putLong(Instant.now().plus(TTL).getEpochSecond()).put((byte) difficulty);
        String body = B64.encodeToString(payload.array());
        return new Challenge(body + "." + sign(body), difficulty);
    }

    /** 核对答案：签名、有效期、答案都对，且这道题没用过；否则报 400 让用户刷新页面重试。 */
    public void verify(String token, String nonce) {
        if (!solved(token, nonce, Instant.now().getEpochSecond())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请刷新页面后重试");
        }
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(USED_PREFIX + token.substring(token.indexOf('.') + 1), "1",
                    TTL.plusMinutes(1));
        } catch (RuntimeException e) {
            log.warn("记录用过的题目失败：{}", e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时不能发验证码，请稍后再试");
        }
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请刷新页面后重试");
        }
    }

    /** 不碰 Redis 的那部分校验：签名对、没过期、答案满足题目里的难度。 */
    boolean solved(String token, String nonce, long nowEpochSecond) {
        if (token == null || token.length() > 200 || !ProofOfWork.validNonce(nonce)) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return false;
        }
        String body = token.substring(0, dot);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (payload.length != PAYLOAD_BYTES) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload, 16, 9);
        long expiresAt = buf.getLong();
        int required = buf.get() & 0xff;
        return nowEpochSecond <= expiresAt && ProofOfWork.solved(token, nonce, required);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
