package com.lifeassistant.core.schedule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_reminder 表实体：一条到点推送的提醒；status 为 PENDING / SENT / FAILED，attempts 是已失败次数，eventId 可为空。 */
@Data
@TableName("t_reminder")
public class Reminder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long eventId;
    private String title;
    private String body;
    private String url;
    private String level;
    private OffsetDateTime remindAt;
    private String status;
    private Integer attempts;
    private String lastError;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;
}
