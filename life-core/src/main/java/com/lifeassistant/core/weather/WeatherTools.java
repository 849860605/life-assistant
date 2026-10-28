package com.lifeassistant.core.weather;

import com.lifeassistant.common.error.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 天气工具，出游、健身（户外跑步）等技能共用。 */
@Component
@RequiredArgsConstructor
public class WeatherTools {

    private final WeatherProvider weatherProvider;

    /** 天数被限制在 1–7；预警查询失败不影响预报返回。 */
    @Tool(description = "查询某地未来几天的逐日天气预报，包含最高最低温、风力、降水、紫外线、日出日落时间，以及当前生效的天气预警。")
    public Map<String, Object> dailyWeather(
            @ToolParam(description = "地名，例如：泰山、杭州西湖、北京；或者 经度,纬度") String location,
            @ToolParam(description = "天数，1 到 7") int days) {
        WeatherProvider.WeatherLocation loc = weatherProvider.resolve(location);
        return Map.of(
                "location", loc,
                "daily", weatherProvider.daily(loc, Math.max(1, Math.min(days, 7))),
                "warnings", safeWarnings(loc));
    }

    /** 逐小时预报附带当前预警，预警查询失败不影响结果。 */
    @Tool(description = "查询某地未来 24 小时的逐小时天气，适合判断爬山、跑步当天几点下雨或起风。")
    public Map<String, Object> hourlyWeather(
            @ToolParam(description = "地名或 经度,纬度") String location) {
        WeatherProvider.WeatherLocation loc = weatherProvider.resolve(location);
        return Map.of("location", loc, "hourly", weatherProvider.hourly(loc), "warnings", safeWarnings(loc));
    }

    /** 查预警但不抛异常：失败或为空时返回给模型看的提示文字，保证预报结果照常返回。 */
    private Object safeWarnings(WeatherProvider.WeatherLocation loc) {
        try {
            List<WeatherProvider.WeatherWarning> warnings = weatherProvider.warnings(loc);
            return warnings.isEmpty() ? "当前没有预警" : warnings;
        } catch (BizException e) {
            return "预警信息暂时查不到";
        }
    }
}
