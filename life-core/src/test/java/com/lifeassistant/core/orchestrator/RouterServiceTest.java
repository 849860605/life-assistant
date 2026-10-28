package com.lifeassistant.core.orchestrator;

import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 简短确认要沿用上一轮技能，且不调路由模型。 */
class RouterServiceTest {

    private final ChatModelRegistry chatModels = mock(ChatModelRegistry.class);
    private final SkillRegistry skillRegistry = mock(SkillRegistry.class);
    private final RouterService router = new RouterService(chatModels, skillRegistry, new LifeProperties(),
            mock(UsageRecorder.class));

    @Test
    void confirmationKeepsLastSkillWithoutCallingModel() {
        when(skillRegistry.contains("travel")).thenReturn(true);

        for (String input : new String[]{"记录一下", "可以", "可以，都存", "好的，保存吧", "只存行程", "OK"}) {
            assertThat(router.route(input, "travel", 1L, "c1").skillId()).as(input).isEqualTo("travel");
        }
        verifyNoInteractions(chatModels);
    }

    @Test
    void recognizesOnlyShortConfirmations() {
        assertThat(RouterService.isFollowUpConfirmation("记录一下")).isTrue();
        assertThat(RouterService.isFollowUpConfirmation("好的")).isTrue();
        assertThat(RouterService.isFollowUpConfirmation("明天早上8点提醒我交电费")).isFalse();
        assertThat(RouterService.isFollowUpConfirmation("可以帮我规划一下下周去杭州玩两天的行程吗")).isFalse();
        assertThat(RouterService.isFollowUpConfirmation("你好")).isFalse();
    }
}
