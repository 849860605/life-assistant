package com.lifeassistant.skill.travel.hiking;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * 给模型用的爬山工具：把 {@link HikingCalculator} 的计算和风险规则包装成 @Tool，负责参数解析。
 */
@Component
public class HikingTools {

    /** 估算爬山用时，fitness 不区分大小写，取值不在 LOW / MEDIUM / HIGH 内会抛异常。 */
    @Tool(description = "按距离、累计爬升、累计下降和体能估算爬山用时（小时）。规划爬山行程时必须用它算时间，不要自己估。")
    public HikingCalculator.Estimate estimateHikingTime(
            @ToolParam(description = "全程距离，公里") double distanceKm,
            @ToolParam(description = "累计爬升，米") int ascentM,
            @ToolParam(description = "累计下降，米；坐缆车下山就填 0") int descentM,
            @ToolParam(description = "体能：LOW / MEDIUM / HIGH；同行有老人或新手按最弱的人算") String fitness) {
        return HikingCalculator.estimate(distanceKm, ascentM, descentM,
                HikingCalculator.Fitness.valueOf(fitness.trim().toUpperCase()));
    }

    /** 算最晚出发时间，固定在日落前留 60 分钟余量；当天走不完时 feasible=false 并给出替代建议。 */
    @Tool(description = """
            根据日落时间和总用时，算出最晚几点必须出发（默认日落前留 60 分钟余量）。
            返回 feasible=false 表示当天走不完，必须按 advice 调整方案，不要自己编一个出发时间。""")
    public Map<String, Object> latestStartTime(
            @ToolParam(description = "日落时间 HH:mm，来自天气工具") String sunset,
            @ToolParam(description = "总用时，小时，来自 estimateHikingTime 的 totalHours") double totalHours) {
        return HikingCalculator.latestStart(LocalTime.parse(sunset.trim()), totalHours, 60)
                .<Map<String, Object>>map(start -> Map.of("feasible", true, "latestStart", start.toString(),
                        "rule", "日落前 60 分钟下到山脚"))
                .orElseGet(() -> Map.of("feasible", false,
                        "advice", "总用时 " + totalHours + " 小时，当天日落前走不完。建议坐缆车缩短路程、分两天走并在山上住宿，或换短一些的路线"));
    }

    /** 估算山顶气温（℃）。 */
    @Tool(description = "按海拔差估算山顶气温（海拔每升高 100 米约降 0.6℃）。")
    public Map<String, Object> summitTemperature(
            @ToolParam(description = "山脚或城市的气温，摄氏度") double baseTempC,
            @ToolParam(description = "山脚海拔，米") int baseElevationM,
            @ToolParam(description = "山顶海拔，米") int summitElevationM) {
        return Map.of("summitTempC", HikingCalculator.summitTemperature(baseTempC, baseElevationM, summitElevationM));
    }

    /** 解析 HH:mm 时间后交给 {@link HikingCalculator#assess} 评估风险，结论由代码规则决定。 */
    @Tool(description = "评估爬山当天的风险等级（LOW / MEDIUM / HIGH）和原因。给出出发建议前必须调用，结论以它为准。")
    public HikingCalculator.RiskAssessment assessHikingRisk(
            @ToolParam(description = "当天是否有雷暴、暴雨、大风等气象预警") boolean thunderOrSevereWarning,
            @ToolParam(description = "当天天气描述，例如：多云、雷阵雨") String weatherText,
            @ToolParam(description = "风力等级，例如 3-4") String windScale,
            @ToolParam(description = "当天降水量，毫米") double precipMm,
            @ToolParam(description = "预计下山时间 HH:mm", required = false) String expectedFinish,
            @ToolParam(description = "日落时间 HH:mm", required = false) String sunset,
            @ToolParam(description = "估算的山顶气温", required = false) Double summitTempC) {
        return HikingCalculator.assess(new HikingCalculator.RiskInput(thunderOrSevereWarning, weatherText, windScale,
                precipMm, parseTime(expectedFinish), parseTime(sunset), summitTempC));
    }

    /** 解析 HH:mm，空串当作未填。 */
    private static LocalTime parseTime(String text) {
        return text == null || text.isBlank() ? null : LocalTime.parse(text.trim());
    }
}
