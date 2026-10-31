package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.card.Card;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话的增删查和每轮更新；凡是带用户身份的操作都先校验会话归属。
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;

    /** 新建会话，ID 用随机 UUID，状态 ACTIVE。 */
    public Conversation create(UserCtx ctx, String title) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setUserId(ctx.userId());
        conversation.setSpaceId(ctx.spaceId());
        conversation.setTitle(title);
        conversation.setStatus("ACTIVE");
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 取会话并校验归属。conversationId 来自前端，不能直接信任，否则会读到别人的会话。
     */
    public Conversation getOwned(UserCtx ctx, String conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.notFound("会话");
        }
        if (conversation.getUserId() != ctx.userId()) {
            throw BizException.forbidden();
        }
        return conversation;
    }

    /** 当前用户的会话，按更新时间倒序，limit 限制在 1–100。 */
    public List<Conversation> list(UserCtx ctx, int limit) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, ctx.userId())
                .orderByDesc(Conversation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    /** 校验归属后取会话最近的消息，limit 限制在 1–500。 */
    public List<ChatMessageEntity> messages(UserCtx ctx, String conversationId, int limit) {
        getOwned(ctx, conversationId);
        return chatMessageMapper.selectRecent(conversationId, Math.min(Math.max(limit, 1), 500));
    }

    /** 每轮对话后更新：最近技能、更新时间；没有标题时用 firstInput 的前 30 个字符当标题。 */
    public void touch(String conversationId, String skillId, String firstInput) {
        LambdaUpdateWrapper<Conversation> update = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getUpdatedAt, OffsetDateTime.now());
        if (skillId != null) {
            update.set(Conversation::getSkillId, skillId);
        }
        conversationMapper.update(update);
        if (firstInput != null) {
            conversationMapper.update(new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId, conversationId)
                    .isNull(Conversation::getTitle)
                    .set(Conversation::getTitle, firstInput.length() > 30 ? firstInput.substring(0, 30) : firstInput));
        }
    }

    /**
     * 把本轮的卡片存到本轮的助手消息上（metadata.cards）。模型这轮一个字没写时助手消息不会入库，就补一条空文本的。
     */
    public void attachCards(String conversationId, List<Card> cards) {
        if (cards.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("cards", cards));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (chatMessageMapper.attachToLastAnswer(conversationId, json) == 0) {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setConversationId(conversationId);
            entity.setRole("ASSISTANT");
            entity.setContent("");
            entity.setMetadata(json);
            chatMessageMapper.insert(entity);
        }
    }

    /** 校验归属后删除会话，消息由外键级联删除。 */
    public void delete(UserCtx ctx, String conversationId) {
        getOwned(ctx, conversationId);
        conversationMapper.deleteById(conversationId);
    }
}
