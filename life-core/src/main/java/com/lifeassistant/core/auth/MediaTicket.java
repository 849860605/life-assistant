package com.lifeassistant.core.auth;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 下载票据：地图图片、行程 PDF 这类用 &lt;image&gt; 或新标签页打开的地址带不了请求头，
 * 用它代替登录 token 放在链接里。只对这几个接口有效，4 小时过期，泄露了也拿不到账号的其他权限。
 * 格式 "用户id.版本.过期秒.签名"，签名是 HMAC-SHA256，密钥由 LIFE_CRYPTO_KEY 派生；没配密钥时每次启动随机生成。
 * 版本是签发时这个用户的票据版本（见 TicketVersions），改密码、重置密码、停用账号时版本加一，之前签的票据随之作废。
 */
@Component
public class MediaTicket {

    public static final Duration TTL = Duration.ofHours(4);

    private final byte[] secret;

    public MediaTicket(LifeProperties properties) {
        String key = properties.getCrypto().getKey();
        byte[] seed;
        if (StringUtils.hasText(key)) {
            seed = ("media-ticket|" + key).getBytes(StandardCharsets.UTF_8);
        } else {
            seed = new byte[32];
            new SecureRandom().nextBytes(seed);
        }
        try {
            this.secret = MessageDigest.getInstance("SHA-256").digest(seed);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 签发的票据和过期时间。 */
    public record Issued(String ticket, Instant expiresAt) {
    }

    /** 票据里带的身份：哪个用户、签发时的票据版本。 */
    public record Claims(long userId, long version) {
    }

    /** 给用户签一张票据，version 是这个用户现在的票据版本。 */
    public Issued issue(long userId, long version) {
        Instant expiresAt = Instant.now().plus(TTL);
        String body = userId + "." + version + "." + expiresAt.getEpochSecond();
        return new Issued(body + "." + sign(body), expiresAt);
    }

    /** 校验签名和有效期，有效时返回票据里的用户和版本；版本和账号状态由调用方再查。 */
    public Optional<Claims> verify(String ticket) {
        if (!StringUtils.hasText(ticket)) {
            return Optional.empty();
        }
        String[] parts = ticket.split("\\.");
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            String body = parts[0] + "." + parts[1] + "." + parts[2];
            boolean signed = MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                    parts[3].getBytes(StandardCharsets.UTF_8));
            if (!signed || Instant.now().getEpochSecond() > Long.parseLong(parts[2])) {
                return Optional.empty();
            }
            return Optional.of(new Claims(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
