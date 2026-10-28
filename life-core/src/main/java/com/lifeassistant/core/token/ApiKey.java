package com.lifeassistant.core.token;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_api_key 表实体：一个个人访问令牌，只存 SHA-256 哈希和前 8 位前缀（用于辨认）；revokedAt 非空即已吊销。 */
@Data
@TableName("t_api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String scope;
    private String keyPrefix;
    private String keyHash;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime createdAt;
}
