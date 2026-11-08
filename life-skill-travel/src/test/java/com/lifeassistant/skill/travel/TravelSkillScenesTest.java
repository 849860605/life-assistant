package com.lifeassistant.skill.travel;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 出游按场景拼提示词和工具：只带选中场景的，多选时合起来，不选时全部。 */
class TravelSkillScenesTest {

    private final TravelSkill skill = new TravelSkill(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void planOnlyCarriesPlanRulesAndQuickTools() {
        String plan = skill.systemPrompt(Set.of("plan"));
        assertThat(plan).contains("【规划行程】").doesNotContain("【单独查询】").doesNotContain("【改已保存的行程】");
        assertThat(plan.length()).isLessThan(skill.systemPrompt().length() / 2 + 1500);
        Set<String> tools = skill.toolNames(Set.of("plan"));
        assertThat(tools).contains("quickHotels", "quickFlights", "saveTripPlan").doesNotContain("compareFlights", "findAndCompareHotels");
    }

    @Test
    void multipleScenesAreMerged() {
        Set<String> tools = skill.toolNames(Set.of("plan", "hiking"));
        assertThat(tools).contains("saveTripPlan", "assessHikingRisk");
        assertThat(skill.systemPrompt(Set.of("plan", "hiking"))).contains("【规划行程】", "【爬山徒步】");
        assertThat(skill.toolCallbacks(Set.of("plan"))).isEmpty();
    }

    @Test
    void everySceneHasRulesAndTools() {
        for (String scene : skill.scenes().keySet()) {
            assertThat(skill.systemPrompt(Set.of(scene))).as(scene).contains("【");
            assertThat(skill.toolNames(Set.of(scene))).as(scene).isNotEmpty();
        }
    }
}
