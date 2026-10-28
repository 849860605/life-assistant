package com.lifeassistant.core.notify;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_notification 表实体：向某个渠道推送一次的发送记录，含是否成功和错误信息；reminderId 为空表示不是提醒触发的。 */
@Data
@TableName("t_notification")
public class NotificationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String channel;
    private String title;
    private Long reminderId;
    private Boolean success;
    private String error;
    private OffsetDateTime createdAt;
}
