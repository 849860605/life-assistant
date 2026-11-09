package com.lifeassistant.skill.travel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP 返回给模型的内容超长要截断。 */
class McpResultCapTest {

    @Test
    void longResultsAreCut() {
        assertThat(TravelSkill.cut("短")).isEqualTo("短");
        assertThat(TravelSkill.cut(null)).isNull();
        String cut = TravelSkill.cut("x".repeat(TravelSkill.MAX_MCP_RESULT + 100));
        assertThat(cut).startsWith("x".repeat(TravelSkill.MAX_MCP_RESULT)).endsWith("截掉了）")
                .hasSizeLessThan(TravelSkill.MAX_MCP_RESULT + 20);
    }
}
