package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.core.config.LifeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 会话记忆：消息全量保存在 t_chat_message，给模型时只取最近 N 条。
 * <p>
 * 没用 Spring AI 的 MessageWindowChatMemory：它每次会用窗口内的消息覆盖整个会话，
 * 超出窗口的历史会被删掉，而我们要保留全部历史给前端展示。
 */
@Component
@RequiredArgsConstructor
public class PersistentChatMemory implements ChatMemory {

    private final ChatMessageMapper chatMessageMapper;
    private final LifeProperties properties;

    /** 追加消息；跳过工具消息和空文本（如只含工具调用的助手消息）。 */
    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.TOOL || !StringUtils.hasText(message.getText())) {
                continue;
            }
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setConversationId(conversationId);
            entity.setRole(message.getMessageType().name());
            entity.setContent(message.getText());
            chatMessageMapper.insert(entity);
        }
    }

    /** 取最近 life.models.memory-window 条消息给模型。 */
    @Override
    public List<Message> get(String conversationId) {
        return chatMessageMapper.selectRecent(conversationId, properties.getModels().getMemoryWindow()).stream()
                .map(PersistentChatMemory::toMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 删除会话的全部消息。 */
    @Override
    public void clear(String conversationId) {
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId));
    }

    /** 数据库行转回 Spring AI 消息；TOOL 返回 null，由调用方过滤。 */
    private static Message toMessage(ChatMessageEntity entity) {
        return switch (MessageType.valueOf(entity.getRole())) {
            case USER -> new UserMessage(entity.getContent());
            case ASSISTANT -> new AssistantMessage(entity.getContent());
            case SYSTEM -> new SystemMessage(entity.getContent());
            case TOOL -> null;
        };
    }
}
