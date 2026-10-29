package com.lifeassistant.skill.travel.hiking;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HikingCalculatorTest {

    @Test
    void estimateUsesNaismithWithFitnessAndRest() {
        // 泰山红门上山约 9 公里、爬升约 1400 米，坐缆车下山
        HikingCalculator.Estimate estimate = HikingCalculator.estimate(9, 1400, 0, HikingCalculator.Fitness.MEDIUM);
        // 9/5 + 1400/600 = 1.8 + 2.333 = 4.13
        assertThat(estimate.movingHours()).isEqualTo(4.1);
        assertThat(estimate.totalHours()).isEqualTo(4.8);

        HikingCalculator.Estimate slower = HikingCalculator.estimate(9, 1400, 0, HikingCalculator.Fitness.LOW);
        assertThat(slower.totalHours()).isGreaterThan(estimate.totalHours());
    }

    @Test
    void estimateRejectsNegativeInput() {
        assertThatThrownBy(() -> HikingCalculator.estimate(-1, 0, 0, HikingCalculator.Fitness.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void latestStartLeavesBufferBeforeSunset() {
        assertThat(HikingCalculator.latestStart(LocalTime.of(18, 30), 4.5, 60)).contains(LocalTime.of(13, 0));
    }

    @Test
    void latestStartIsEmptyWhenHikeCannotFinishSameDay() {
        // 日落 17:00，总用时 17 小时 + 60 分钟余量，需要前一天 23:00 出发：不能绕到前一天，返回空
        assertThat(HikingCalculator.latestStart(LocalTime.of(17, 0), 17, 60)).isEmpty();
        // 刚好从 0 点出发还来得及
        assertThat(HikingCalculator.latestStart(LocalTime.of(17, 0), 16, 60)).contains(LocalTime.MIDNIGHT);
    }

    @Test
    void latestStartToolReportsInfeasiblePlan() {
        HikingTools tools = new HikingTools();
        assertThat(tools.latestStartTime("17:00", 17)).containsEntry("feasible", false).containsKey("advice");
        assertThat(tools.latestStartTime("18:30", 4.5)).containsEntry("feasible", true).containsEntry("latestStart", "13:00");
    }

    @Test
    void summitTemperatureDropsWithElevation() {
        assertThat(HikingCalculator.summitTemperature(20, 150, 1545)).isEqualTo(11.6);
    }

    @Test
    void thunderIsHighRisk() {
        HikingCalculator.RiskAssessment risk = HikingCalculator.assess(new HikingCalculator.RiskInput(
                false, "雷阵雨", "3-4", 1.0, LocalTime.of(15, 0), LocalTime.of(18, 30), 12.0));
        assertThat(risk.level()).isEqualTo(HikingCalculator.RiskLevel.HIGH);
    }

    @Test
    void finishingAfterSunsetIsHighRisk() {
        HikingCalculator.RiskAssessment risk = HikingCalculator.assess(new HikingCalculator.RiskInput(
                false, "晴", "1-2", 0, LocalTime.of(19, 0), LocalTime.of(18, 30), 15.0));
        assertThat(risk.level()).isEqualTo(HikingCalculator.RiskLevel.HIGH);
    }

    @Test
    void windFiveAndColdSummitIsMediumRisk() {
        HikingCalculator.RiskAssessment risk = HikingCalculator.assess(new HikingCalculator.RiskInput(
                false, "多云", "4-5", 0, LocalTime.of(15, 0), LocalTime.of(18, 30), -2.0));
        assertThat(risk.level()).isEqualTo(HikingCalculator.RiskLevel.MEDIUM);
        assertThat(risk.reasons()).hasSize(2);
    }

    @Test
    void calmDayIsLowRisk() {
        HikingCalculator.RiskAssessment risk = HikingCalculator.assess(new HikingCalculator.RiskInput(
                false, "晴", "1-2", 0, LocalTime.of(15, 0), LocalTime.of(18, 30), 15.0));
        assertThat(risk.level()).isEqualTo(HikingCalculator.RiskLevel.LOW);
    }

    @Test
    void maxNumberParsesWindRange() {
        assertThat(HikingCalculator.maxNumber("3-4")).isEqualTo(4);
        assertThat(HikingCalculator.maxNumber(null)).isZero();
    }

    @Test
    void weatherTextIsUsedWhenPrecipitationIsUnknown() {
        // 高德没有降水量（填 0），只能看天气描述
        assertThat(riskFor("大雨").level()).isEqualTo(HikingCalculator.RiskLevel.HIGH);
        assertThat(riskFor("小雨转中雨").level()).isEqualTo(HikingCalculator.RiskLevel.MEDIUM);
        assertThat(riskFor("小雪").level()).isEqualTo(HikingCalculator.RiskLevel.MEDIUM);
        assertThat(riskFor("多云").level()).isEqualTo(HikingCalculator.RiskLevel.LOW);
    }

    private static HikingCalculator.RiskAssessment riskFor(String weatherText) {
        return HikingCalculator.assess(new HikingCalculator.RiskInput(false, weatherText, "1-3", 0,
                LocalTime.of(15, 0), LocalTime.of(17, 30), 12.0));
    }
}
