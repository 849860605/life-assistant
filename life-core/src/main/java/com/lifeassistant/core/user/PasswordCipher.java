package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密码在浏览器里先加密再提交，请求里看不到明文：RSA-3072，OAEP 填充（SHA-256，MGF1 也用 SHA-256）。
 * 密钥对每次启动重新生成、只在内存里；前端每次提交前领一次公钥和服务器时间，把"服务器时间\n密码"加密成 base64 提交。
 * 解开后时间超过 5 分钟的不认，同一段密文只认一次（Redis 里记下），截获的密文拿去重放也登不上。
 * 只认加密过的密码，明文提交（还没刷新的旧页面）会提示刷新页面。防网络窃听靠的是 HTTPS，这一层只保证请求里不出现明文密码。
 */
@Slf4j
@Component
public class PasswordCipher {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final String USED_PREFIX = "pwd:used:";
    /** 3072 位密钥 OAEP-SHA256 最多装 318 字节，64 位密码按 UTF-8 最长 256 字节，加上时间戳也装得下 */
    private static final int KEY_BITS = 3072;
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT);

    private final KeyPair keys;
    private final StringRedisTemplate redis;

    @Autowired
    public PasswordCipher(StringRedisTemplate redis) {
        this(generate(), redis);
    }

    PasswordCipher(KeyPair keys, StringRedisTemplate redis) {
        this.keys = keys;
        this.redis = redis;
    }

    /** 给前端的公钥：n、e 是十六进制的模数和指数，time 是服务器当前毫秒数（加密时带上，前端不用管本机时钟准不准）。 */
    public record LoginKey(String n, String e, long time) {
    }

    /** 当前公钥和服务器时间。 */
    public LoginKey loginKey() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        return new LoginKey(pub.getModulus().toString(16), pub.getPublicExponent().toString(16), System.currentTimeMillis());
    }

    /** 把请求里加密的密码还原成明文：解密并核对时间、这段密文没用过；解不开（包括明文提交）报"请刷新页面后重试"。 */
    public String open(String value) {
        if (value == null) {
            throw stale();
        }
        String password = decrypt(value, System.currentTimeMillis());
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(USED_PREFIX + digest(value), "1", TTL.plusMinutes(1));
        } catch (RuntimeException e) {
            log.warn("记录用过的密码密文失败：{}", e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时无法处理，请稍后再试");
        }
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请刷新页面后重试");
        }
        return password;
    }

    /** 不碰 Redis 的那部分：解密、拆出时间和密码、时间在 5 分钟内。解不开、格式不对、过期都报"请刷新页面后重试"。 */
    String decrypt(String base64, long now) {
        if (base64.length() > 1024) {
            throw stale();
        }
        String plain;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate(), OAEP);
            plain = new String(cipher.doFinal(Base64.getDecoder().decode(base64)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // 重启换了密钥，或者密文被改过
            throw stale();
        }
        int nl = plain.indexOf('\n');
        long time;
        try {
            time = nl > 0 ? Long.parseLong(plain.substring(0, nl)) : -1;
        } catch (NumberFormatException e) {
            time = -1;
        }
        // 允许前后 5 分钟：服务器时间是前端从 login-key 拿的，正常只会比现在早几秒
        if (time < 0 || Math.abs(now - time) > TTL.toMillis()) {
            throw stale();
        }
        return plain.substring(nl + 1);
    }

    private static BizException stale() {
        return new BizException(ErrorCode.BAD_REQUEST, "请刷新页面后重试");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_BITS);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
