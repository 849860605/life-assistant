package com.lifeassistant.core.token;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.error.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 个人访问令牌。明文只在创建时返回一次，数据库里只存 SHA-256。
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "lk_";

    private final ApiKeyMapper apiKeyMapper;
    private final SecureRandom random = new SecureRandom();

    /** 创建结果；plainKey 是唯一一次能拿到的明文。 */
    public record Created(ApiKey apiKey, String plainKey) {
    }

    /** 生成 lk_ 开头的 24 字节随机令牌，库里只存哈希。 */
    public Created create(long userId, String name, ApiKeyScope scope) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String plain = PREFIX + HexFormat.of().formatHex(bytes);
        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setName(name);
        key.setScope(scope.name());
        key.setKeyPrefix(plain.substring(0, 8));
        key.setKeyHash(sha256(plain));
        apiKeyMapper.insert(key);
        return new Created(key, plain);
    }

    /** 校验令牌并返回所属用户，通过时顺带更新最后使用时间；令牌不存在、已吊销或用途不符都返回空。 */
    public Optional<Long> verify(String plainKey, ApiKeyScope scope) {
        if (plainKey == null || !plainKey.startsWith(PREFIX)) {
            return Optional.empty();
        }
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, sha256(plainKey))
                .isNull(ApiKey::getRevokedAt));
        if (key == null || !scope.name().equals(key.getScope())) {
            return Optional.empty();
        }
        apiKeyMapper.update(new LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, key.getId())
                .set(ApiKey::getLastUsedAt, OffsetDateTime.now()));
        return Optional.of(key.getUserId());
    }

    /** 列出用户的全部令牌（含已吊销），新的在前。 */
    public List<ApiKey> list(long userId) {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, userId)
                .orderByDesc(ApiKey::getId));
    }

    /** 吊销令牌（写 revokedAt），不存在或不属于该用户报 404。 */
    public void revoke(long userId, long keyId) {
        int updated = apiKeyMapper.update(new LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, keyId)
                .eq(ApiKey::getUserId, userId)
                .set(ApiKey::getRevokedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.notFound("令牌");
        }
    }

    /** 计算 SHA-256，返回小写十六进制。 */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
