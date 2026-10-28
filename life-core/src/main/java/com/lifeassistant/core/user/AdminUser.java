package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_admin_user 表实体：一个管理员账号，和普通用户（t_user）是两套独立体系；status=1 为启用。 */
@Data
@TableName("t_admin_user")
public class AdminUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private Integer status;
    private OffsetDateTime createdAt;
}
