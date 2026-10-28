package com.lifeassistant.core.notify;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_notify_channel 表实体：用户的一个推送渠道；configEnc 是加密后的渠道配置 JSON（如 Bark 设备 key）。 */
@Data
@TableName("t_notify_channel")
public class NotifyChannel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String configEnc;
    private Boolean enabled;
    private OffsetDateTime createdAt;
}
