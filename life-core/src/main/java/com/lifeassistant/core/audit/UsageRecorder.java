package com.lifeassistant.core.audit;

import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 记录每次模型调用的 Token 用量和费用，用于成本统计和以后计费。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageRecorder {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmUsageMapper usageMapper;
    private final ChatModelRegistry chatModels;

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
            row.setCachedTokens(cachedTokens(usage));
            row.setCost(cost(chatModels.price(model), row));
            usageMapper.insert(row);
        } catch (RuntimeException e) {
            // 用量记录失败不影响对话
            log.warn("记录用量失败：{}", e.getMessage());
        }
    }

    /**
     * OpenAI 兼容接口的原始用量里带缓存命中数；多轮工具调用合并后的用量没有原始数据，返回 null。
     */
    static Integer cachedTokens(Usage usage) {
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage raw && raw.promptTokensDetails() != null) {
            return raw.promptTokensDetails().cachedTokens();
        }
        return null;
    }

    /** 按单价算费用（元）；不知道缓存命中数时全按未命中算，是上限。没配单价返回 null。 */
    static BigDecimal cost(LifeProperties.Price price, LlmUsage row) {
        if (price == null) {
            return null;
        }
        int cached = row.getCachedTokens() == null ? 0 : row.getCachedTokens();
        BigDecimal yuan = price.getInput().multiply(BigDecimal.valueOf(row.getPromptTokens() - cached))
                .add(price.getCachedInput().multiply(BigDecimal.valueOf(cached)))
                .add(price.getOutput().multiply(BigDecimal.valueOf(row.getCompletionTokens())));
        return yuan.divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
