package com.lifeassistant.core.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * t_memory 表实体，一行是关于用户的一条长期记忆；sensitive 为 true 时 content 是密文。
 */
@Data
@TableName("t_memory")
public class MemoryItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long spaceId;
    private String category;
    private String memKey;
    private String content;
    private String source;
    private BigDecimal confidence;
    private Boolean sensitive;
    private OffsetDateTime expireAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
