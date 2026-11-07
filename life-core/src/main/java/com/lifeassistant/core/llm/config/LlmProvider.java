package com.lifeassistant.core.llm.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_llm_provider 表实体：一个模型提供方的连接配置，apiKeyEnc 是加密后的 Key。 */
@Data
@TableName("t_llm_provider")
public class LlmProvider {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String type;
    private String baseUrl;
    private String completionsPath;
    private String apiKeyEnc;
    private String authHeader;
    /** 关掉思考的额外请求参数，JSON 文本 */
    private String noThinking;
    /** 思考参数格式：QWEN / TYPE / OPENAI / CUSTOM / NONE */
    private String thinkingStyle;
    private Boolean enabled;
    private Integer sort;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
