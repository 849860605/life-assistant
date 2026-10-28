package com.lifeassistant.core.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * t_admin_op_log 表实体，一行是管理员的一次操作记录。
 */
@Data
@TableName("t_admin_op_log")
public class AdminOpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminId;
    private String action;
    private String target;
    private String detail;
    private OffsetDateTime createdAt;
}
