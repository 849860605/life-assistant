package com.lifeassistant.core.llm.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_llm_role 表实体：一个用途（ROUTER、SKILL、VISION）用哪个模型。 */
@Data
@TableName("t_llm_role")
public class LlmRole {
    @TableId(type = IdType.INPUT)
    private String role;
    private Long modelId;
    private OffsetDateTime updatedAt;
}
