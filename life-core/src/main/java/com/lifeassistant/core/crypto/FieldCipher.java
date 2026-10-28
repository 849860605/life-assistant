package com.lifeassistant.core.crypto;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加密（AES-256-GCM）。密钥来自环境变量，不进数据库。
 * 密文格式：Base64(12 字节 IV + 密文和认证标签)。
 */
@Component
public class FieldCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Spring 用：从 life.crypto.key 读密钥。 */
    @Autowired
    public FieldCipher(LifeProperties properties) {
        this(properties.getCrypto().getKey());
    }

    /** 校验并加载密钥，缺失或不是 32 字节时启动失败；测试可直接传密钥。 */
    public FieldCipher(String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            throw new IllegalStateException("缺少 LIFE_CRYPTO_KEY。用 `openssl rand -base64 32` 生成后写进 .env");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("LIFE_CRYPTO_KEY 必须是 Base64 编码的 32 字节，当前是 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密，每次随机 IV，同一明文的密文也不同；null 原样返回。 */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密并校验认证标签，密文被改或密钥不对时抛 IllegalStateException；null 原样返回。 */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("解密失败，可能是 LIFE_CRYPTO_KEY 变了", e);
        }
    }
}
