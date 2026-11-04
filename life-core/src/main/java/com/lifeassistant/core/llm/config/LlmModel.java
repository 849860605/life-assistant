package com.lifeassistant.core.llm.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** t_llm_model 表实体：某个提供方下的一个型号，带单价和能不能给 App 用户选。 */
@Data
@TableName("t_llm_model")
public class LlmModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String providerCode;
    private String model;
    private String displayName;
    private String description;
    private Boolean enabled;
    private Boolean userSelectable;
    private Boolean supportsTools;
    private BigDecimal priceInput;
    private BigDecimal priceCachedInput;
    private BigDecimal priceOutput;
    private Integer sort;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** "提供方:型号"，和 ModelSpec.id() 一致。 */
    public String spec() {
        return providerCode + ":" + model;
    }
}
