package com.lifeassistant.core.audit;

import com.lifeassistant.core.config.LifeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.openai.api.OpenAiApi;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证缓存命中数的读取和费用计算（DeepSeek flash 高峰价：未命中 2、命中 0.04、输出 8 元/百万 Token）。 */
class UsageRecorderTest {

    private final LifeProperties.Price flash = price("2", "0.04", "8");

    @Test
    void costWithCacheHits() {
        LlmUsage row = row(100_000, 80_000, 2_000);

        // 2 万未命中 × 2 + 8 万命中 × 0.04 + 2 千输出 × 8 = 0.04 + 0.0032 + 0.016
        assertThat(UsageRecorder.cost(flash, row)).isEqualByComparingTo("0.0592");
    }

    @Test
    void unknownCacheCountsAsMiss() {
        LlmUsage row = row(100_000, null, 2_000);

        assertThat(UsageRecorder.cost(flash, row)).isEqualByComparingTo("0.216");
    }

    @Test
    void noPriceNoCost() {
        assertThat(UsageRecorder.cost(null, row(1000, null, 10))).isNull();
    }

    @Test
    void cachedTokensFromOpenAiUsage() {
        OpenAiApi.Usage raw = new OpenAiApi.Usage(10, 100, 110,
                new OpenAiApi.Usage.PromptTokensDetails(null, 64), null);

        assertThat(UsageRecorder.cachedTokens(new DefaultUsage(100, 10, 110, raw))).isEqualTo(64);
        assertThat(UsageRecorder.cachedTokens(new DefaultUsage(100, 10))).isNull();
    }

    private static LlmUsage row(int prompt, Integer cached, int completion) {
        LlmUsage row = new LlmUsage();
        row.setPromptTokens(prompt);
        row.setCachedTokens(cached);
        row.setCompletionTokens(completion);
        return row;
    }

    private static LifeProperties.Price price(String input, String cached, String output) {
        LifeProperties.Price price = new LifeProperties.Price();
        price.setInput(new BigDecimal(input));
        price.setCachedInput(new BigDecimal(cached));
        price.setOutput(new BigDecimal(output));
        return price;
    }
}
