package com.lifeassistant.core.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
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
    /** 缓存命中的输入 Token 数，接口没返回时为空 */
    private Integer cachedTokens;
    /** 费用（元），没配单价时为空 */
    private BigDecimal cost;
    private OffsetDateTime createdAt;
}
