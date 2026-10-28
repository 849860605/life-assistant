package com.lifeassistant.core.schedule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_schedule_event 表实体：用户的一条日程；source/sourceRef 记录由谁创建及来源方的关联 id。 */
@Data
@TableName("t_schedule_event")
public class ScheduleEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long spaceId;
    private String title;
    private String description;
    private String location;
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private Boolean allDay;
    private String source;
    private String sourceRef;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
