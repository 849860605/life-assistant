package com.lifeassistant.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.crypto.FieldCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一记忆。用户可以在"我的"页面查看、修改、删除；AI 通过 MemoryTools 写入。
 */
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryMapper memoryMapper;
    private final FieldCipher cipher;

    /** 写入或覆盖一条记忆（同一用户下 category + key 唯一）；敏感类加密，AI 推断的置信度记 0.70。 */
    public MemoryItem upsert(UserCtx ctx, MemoryCategory category, String key, String content,
                             MemorySource source, OffsetDateTime expireAt) {
        MemoryItem item = memoryMapper.selectOne(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getUserId, ctx.userId())
                .eq(MemoryItem::getCategory, category.name())
                .eq(MemoryItem::getMemKey, key));
        boolean isNew = item == null;
        if (isNew) {
            item = new MemoryItem();
            item.setUserId(ctx.userId());
            item.setSpaceId(ctx.spaceId());
            item.setCategory(category.name());
            item.setMemKey(key);
        }
        item.setSensitive(category.sensitive());
        item.setContent(category.sensitive() ? cipher.encrypt(content) : content);
        item.setSource(source.name());
        item.setConfidence(source == MemorySource.AI_INFERRED ? new BigDecimal("0.70") : BigDecimal.ONE);
        item.setExpireAt(expireAt);
        item.setUpdatedAt(OffsetDateTime.now());
        if (isNew) {
            memoryMapper.insert(item);
        } else {
            memoryMapper.updateById(item);
        }
        return item;
    }

    /** 给用户自己看：列出全部记忆（含已过期），敏感记忆解密后返回。 */
    public List<MemoryItem> listForOwner(UserCtx ctx) {
        return memoryMapper.selectList(new LambdaQueryWrapper<MemoryItem>()
                        .eq(MemoryItem::getUserId, ctx.userId())
                        .orderByAsc(MemoryItem::getCategory, MemoryItem::getMemKey))
                .stream()
                .peek(m -> {
                    if (Boolean.TRUE.equals(m.getSensitive())) {
                        m.setContent(cipher.decrypt(m.getContent()));
                    }
                })
                .toList();
    }

    /** 按 ID 删除自己的记忆，不存在或不属于当前用户时报未找到。 */
    public void delete(UserCtx ctx, long memoryId) {
        int deleted = memoryMapper.delete(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getId, memoryId)
                .eq(MemoryItem::getUserId, ctx.userId()));
        if (deleted == 0) {
            throw BizException.notFound("记忆");
        }
    }

    /** 按 category + key 删除，返回删除条数；没有匹配返回 0，不报错（给 AI 的遗忘工具用）。 */
    public int deleteByKey(UserCtx ctx, MemoryCategory category, String key) {
        return memoryMapper.delete(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getUserId, ctx.userId())
                .eq(MemoryItem::getCategory, category.name())
                .eq(MemoryItem::getMemKey, key));
    }

    /** 这个用户现在有多少条记忆（AI 写新记忆前检查上限用）。 */
    public long count(UserCtx ctx) {
        return memoryMapper.selectCount(new LambdaQueryWrapper<MemoryItem>().eq(MemoryItem::getUserId, ctx.userId()));
    }

    /** 同一用户下这个 category + key 是不是已经有记忆（有就是覆盖，不占新名额）。 */
    public boolean exists(UserCtx ctx, MemoryCategory category, String key) {
        return memoryMapper.selectCount(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getUserId, ctx.userId())
                .eq(MemoryItem::getCategory, category.name())
                .eq(MemoryItem::getMemKey, key)) > 0;
    }

    /**
     * 拼进 system prompt 的记忆片段：只包含未过期的非敏感记忆。
     * 敏感记忆需要时由具体技能按需读取。内容去掉换行和附件、代码的分隔符，AI 推断的标上"推测"。
     */
    public String promptSnippet(UserCtx ctx) {
        OffsetDateTime now = OffsetDateTime.now();
        List<MemoryItem> items = memoryMapper.selectList(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getUserId, ctx.userId())
                .eq(MemoryItem::getSensitive, false)
                .and(w -> w.isNull(MemoryItem::getExpireAt).or().gt(MemoryItem::getExpireAt, now))
                .orderByAsc(MemoryItem::getCategory));
        if (items.isEmpty()) {
            return "（暂无）";
        }
        return items.stream()
                .map(m -> "- [%s/%s] %s%s".formatted(m.getCategory(), sanitize(m.getMemKey(), 64),
                        sanitize(m.getContent(), PROMPT_MAX),
                        MemorySource.AI_INFERRED.name().equals(m.getSource()) ? "（推测，未经用户确认）" : ""))
                .collect(Collectors.joining("\n"));
    }

    /** 拼进提示词时每条记忆最多多少字 */
    static final int PROMPT_MAX = 200;

    /** 记忆内容放进提示词前清理：换行、〔〕和反引号换成空格或去掉，超长截断，避免伪造附件块、代码块或另起一段"指令"。 */
    static String sanitize(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("[\\r\\n\\u2028\\u2029]+", " ").replaceAll("[〔〕`]", "").strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
