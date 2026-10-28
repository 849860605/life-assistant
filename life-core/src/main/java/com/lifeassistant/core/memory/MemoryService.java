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

    /**
     * 拼进 system prompt 的记忆片段：只包含未过期的非敏感记忆。
     * 敏感记忆需要时由具体技能按需读取。
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
                .map(m -> "- [%s/%s] %s".formatted(m.getCategory(), m.getMemKey(), m.getContent()))
                .collect(Collectors.joining("\n"));
    }
}
