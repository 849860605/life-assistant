package com.lifeassistant.core.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * t_llm_usage 表实体，一行是一次模型调用的 Token 用量。
 */
@Data
@TableName("t_llm_usage")
public class LlmUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String conversationId;
    private String purpose;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private OffsetDateTime createdAt;
}
