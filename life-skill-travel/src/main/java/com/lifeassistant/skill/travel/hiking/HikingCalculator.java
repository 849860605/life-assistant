package com.lifeassistant.skill.travel.hiking;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬山相关的计算和安全规则。安全结论由代码给出，不交给模型判断。
 */
public final class HikingCalculator {

    /** 休息、拍照、吃东西额外加 15% */
    private static final double REST_FACTOR = 1.15;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private HikingCalculator() {
    }

    /** 体能档位，factor 乘在行进用时上：弱 ×1.3，中 ×1.0，强 ×0.85。 */
    public enum Fitness {
        LOW(1.3), MEDIUM(1.0), HIGH(0.85);

        final double factor;

        Fitness(double factor) {
            this.factor = factor;
        }
    }

    /** 爬山风险等级，HIGH 表示建议改期或换路线。 */
    public enum RiskLevel {LOW, MEDIUM, HIGH}

    /** 用时估算结果（小时，保留 1 位小数）：movingHours 纯行进，totalHours 含休息；method 说明算法。 */
    public record Estimate(double movingHours, double totalHours, String method) {
    }

    /**
     * 风险评估的输入：当天天气、风力（如 "3-4"）、降水（毫米）、预计下山与日落时间、山顶气温（℃）。
     * 后三项可为空，为空时跳过对应规则。
     */
    public record RiskInput(boolean thunderOrSevereWarning, String weatherText, String windScale, double precipMm,
                            LocalTime expectedFinish, LocalTime sunset, Double summitTempC) {
    }

    /** 风险评估结果：等级、触发的原因列表、出发建议。 */
    public record RiskAssessment(RiskLevel level, List<String> reasons, String advice) {
    }

    /**
     * 估算爬山用时（小时）。Naismith 规则：每 5 公里 1 小时，每爬升 600 米加 1 小时；
     * 下山台阶多时对膝盖和速度影响大，这里每下降 1000 米再加 1 小时（偏保守）。
     * 结果乘体能系数得到行进用时，再乘 1.15 计入休息得到总用时；参数为负抛 IllegalArgumentException。
     */
    public static Estimate estimate(double distanceKm, int ascentM, int descentM, Fitness fitness) {
        if (distanceKm < 0 || ascentM < 0 || descentM < 0) {
            throw new IllegalArgumentException("距离和爬升不能是负数");
        }
        double moving = (distanceKm / 5.0 + ascentM / 600.0 + descentM / 1000.0) * fitness.factor;
        return new Estimate(round1(moving), round1(moving * REST_FACTOR),
                "Naismith 规则，体能系数 " + fitness.factor + "，含 15% 休息时间");
    }

    /**
     * 最晚出发时间 = 日落 − 总用时（小时）− bufferMinutes（分钟），即日落前留出余量下到山脚。
     * 算出来早于当天 0 点时返回空，表示当天走不完，不能把时间绕到前一天晚上。
     */
    public static Optional<LocalTime> latestStart(LocalTime sunset, double totalHours, int bufferMinutes) {
        long minutes = Math.round(totalHours * 60) + bufferMinutes;
        long sunsetMinutes = sunset.getHour() * 60L + sunset.getMinute();
        if (minutes > sunsetMinutes) {
            return Optional.empty();
        }
        return Optional.of(sunset.minusMinutes(minutes));
    }

    /** 估算山顶气温（℃，保留 1 位小数）：海拔每升高 100 米气温约降 0.6℃。 */
    public static double summitTemperature(double baseTempC, int baseElevationM, int summitElevationM) {
        return round1(baseTempC - 0.6 * (summitElevationM - baseElevationM) / 100.0);
    }

    /**
     * 按固定规则评估当天风险，有任一 HIGH 条件即为 HIGH，否则有 MEDIUM 条件为 MEDIUM，都没有为 LOW。
     * HIGH：雷暴或气象预警（含天气描述带“雷”）、风力 ≥ 6 级、降水 ≥ 10 mm、预计下山晚于日落。
     * MEDIUM：风力 5 级、降水 ≥ 2 mm、下山距日落不到 1 小时、山顶气温 ≤ 0℃。
     */
    public static RiskAssessment assess(RiskInput input) {
        List<String> high = new ArrayList<>();
        List<String> medium = new ArrayList<>();

        String text = input.weatherText() == null ? "" : input.weatherText();
        if (input.thunderOrSevereWarning() || text.contains("雷")) {
            high.add("有雷暴或气象预警，山顶和山脊遭雷击风险高");
        }
        int wind = maxNumber(input.windScale());
        if (wind >= 6) {
            high.add("风力 " + wind + " 级，山脊和栈道上很危险");
        } else if (wind == 5) {
            medium.add("风力 5 级，山顶体感会明显更冷，注意防风");
        }
        if (input.precipMm() >= 10) {
            high.add("降水 " + input.precipMm() + " 毫米，路面湿滑，有山洪和落石风险");
        } else if (input.precipMm() >= 2) {
            medium.add("有降水，台阶湿滑，带好雨具和防滑鞋");
        }
        if (input.expectedFinish() != null && input.sunset() != null) {
            if (input.expectedFinish().isAfter(input.sunset())) {
                high.add("预计 " + input.expectedFinish() + " 才能下山，晚于日落 " + input.sunset());
            } else if (input.expectedFinish().isAfter(input.sunset().minusMinutes(60))) {
                medium.add("预计下山时间离日落不到 1 小时，带头灯，避免拖延");
            }
        }
        if (input.summitTempC() != null && input.summitTempC() <= 0) {
            medium.add("山顶气温约 " + input.summitTempC() + "℃，需要保暖衣物和手套");
        }

        if (!high.isEmpty()) {
            high.addAll(medium);
            return new RiskAssessment(RiskLevel.HIGH, high, "不建议按原计划出发，建议改期或换成低海拔、短距离的路线");
        }
        if (!medium.isEmpty()) {
            return new RiskAssessment(RiskLevel.MEDIUM, medium, "可以出发，但要按提示做好准备，并预留撤退时间");
        }
        return new RiskAssessment(RiskLevel.LOW, List.of("未发现明显风险"), "按计划出发，注意补水和防晒");
    }

    /** 取文本里最大的整数，用于解析风力等级："3-4" 取 4；空值或没有数字当 0。 */
    static int maxNumber(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = NUMBER.matcher(text);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group()));
        }
        return max;
    }

    /** 四舍五入保留 1 位小数。 */
    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
