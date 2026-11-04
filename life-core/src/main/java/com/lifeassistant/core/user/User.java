package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_user 表实体：一个普通用户；timezone 决定日程和提醒的时间换算，phoneEnc 是加密后的手机号，status=1 为启用。 */
@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String nickname;
    private String phoneEnc;
    private String timezone;
    private Integer status;
    private OffsetDateTime lastLoginAt;
    /** 自己选的对话模型（t_llm_model.id），空表示用默认 */
    private Long preferredModelId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
