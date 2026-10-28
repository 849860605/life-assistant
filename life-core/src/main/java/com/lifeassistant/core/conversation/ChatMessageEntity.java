package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * t_chat_message 表实体，一行是会话里的一条消息；role 存 Spring AI 的 MessageType 名。
 */
@Data
@TableName("t_chat_message")
public class ChatMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String role;
    private String content;
    private String metadata;
    private OffsetDateTime createdAt;
}
