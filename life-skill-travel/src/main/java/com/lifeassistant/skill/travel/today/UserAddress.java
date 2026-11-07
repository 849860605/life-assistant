package com.lifeassistant.skill.travel.today;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一个常用地址，对应 t_user_address；isDefault 的那个是出发默认地址。 */
@Data
@TableName("t_user_address")
public class UserAddress {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonIgnore
    private Long userId;
    private String label;
    private String address;
    @TableField("is_default")
    private Boolean isDefault;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
