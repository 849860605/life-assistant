package com.lifeassistant.core.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/** 记录每次模型调用的 Token 用量，用于成本统计和以后计费。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageRecorder {

    private final LlmUsageMapper usageMapper;

    /** 落一条用量记录；usage 为空直接跳过，写库失败只打日志，不影响对话。 */
    public void record(Long userId, String conversationId, String purpose, String model, Usage usage) {
        if (usage == null) {
            return;
        }
        try {
            LlmUsage row = new LlmUsage();
            row.setUserId(userId);
            row.setConversationId(conversationId);
            row.setPurpose(purpose);
            row.setModel(model);
            row.setPromptTokens(usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
            row.setCompletionTokens(usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
            usageMapper.insert(row);
        } catch (RuntimeException e) {
            // 用量记录失败不影响对话
            log.warn("记录用量失败：{}", e.getMessage());
        }
    }
}
