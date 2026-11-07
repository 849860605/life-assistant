package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * t_conversation 表实体，一行是一个会话；ID 由应用生成 UUID，skillId 是最近一次处理该会话的技能。
 */
@Data
@TableName("t_conversation")
public class Conversation {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private Long spaceId;
    private String title;
    private String skillId;
    private String status;
    private String summary;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 置顶时间，为空表示没置顶 */
    private OffsetDateTime pinnedAt;
}
