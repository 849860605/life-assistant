package com.lifeassistant.core.brief;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** t_brief_setting 表实体：一个用户的每日简报设置。 */
@Data
@TableName("t_brief_setting")
public class BriefSetting {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private Boolean enabled;
    private String sendTime;
    private String city;
    private LocalDate lastSentOn;
    private OffsetDateTime updatedAt;
}
