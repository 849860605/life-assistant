package com.lifeassistant.core.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 相对日期表：周中、周六、周日三种情况下这周末、下周末都算对。 */
class RelativeDatesTest {

    @Test
    void midWeek() {
        // 2026-11-04 是周三
        String s = SkillRunner.relativeDates(LocalDate.of(2026, 11, 4));
        assertThat(s).contains("明天 2026-11-05（周四）").contains("后天 2026-11-06（周五）")
                .contains("这周末 2026-11-07～2026-11-08").contains("下周末 2026-11-14～2026-11-15")
                .contains("下周一 2026-11-09").contains("下个月初 2026-12-01");
    }

    @Test
    void sunday() {
        // 2026-11-08 是周日：这周末只剩今天，下周末是下一周的周六周日
        String s = SkillRunner.relativeDates(LocalDate.of(2026, 11, 8));
        assertThat(s).contains("这周末只剩今天（周日）2026-11-08").contains("下周末 2026-11-14～2026-11-15")
                .contains("后天 2026-11-10（周二）");
    }

    @Test
    void saturdayAndMonthEnd() {
        // 2026-10-31 是周六、月底
        String s = SkillRunner.relativeDates(LocalDate.of(2026, 10, 31));
        assertThat(s).contains("这周末 2026-10-31～2026-11-01").contains("下周末 2026-11-07～2026-11-08")
                .contains("下个月初 2026-11-01");
    }
}
