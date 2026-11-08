package com.lifeassistant.core.weather;

import com.lifeassistant.common.error.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 天气工具，出游、健身（户外跑步）等技能共用。结果里带 source，模型据此知道哪些数据缺失。 */
@Component
@RequiredArgsConstructor
public class WeatherTools {

    private final WeatherService weatherService;

    /** 预报最远能看到今天起第几天（和风、高德都是 7 天内） */
    private static final int FORECAST_DAYS = 7;
    /** 白天最高温到这个数算热，户外要避开中午 */
    private static final int HOT = 33;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 天数被限制在 1–7；预警查询失败不影响预报返回。
     * 给了出行日期时：出发日超出预报范围就不查（省一次调用），返回提示让模型按季节常识说；
     * 在范围内只返回出行那几天，每天标好有没有雨、热不热，模型照着排。
     */
    @Tool(description = """
            查询某地未来几天的逐日天气预报，包含最高最低温、风力、降水、日出日落时间，以及当前生效的天气预警。
            为出行查时填 startDate、endDate：只返回出行那几天，每天带 rainy（有雨雪）、hot（最高温≥33°C）；
            再填 from（出发城市）时另外返回出发地在出发、回来那两天的天气（fromDays）和两地温差（compare），用来给穿衣、带什么的建议。
            出发日超出预报范围（今天起 7 天以外）时返回 outOfRange 和 hint，不要编预报，按 hint 说。
            source 会说明数据来源缺哪些项，缺的不要编；用户没问到的缺项（比如逐小时、预警）不用在回答里提。""")
    public Map<String, Object> dailyWeather(
            @ToolParam(description = "地名，例如：泰山、杭州西湖、北京；或者 经度,纬度") String location,
            @ToolParam(description = "天数，1 到 7；填了 startDate 时不用填", required = false) Integer days,
            @ToolParam(description = "出行第一天 yyyy-MM-dd，为行程查天气时填", required = false) String startDate,
            @ToolParam(description = "出行最后一天 yyyy-MM-dd", required = false) String endDate,
            @ToolParam(description = "出发城市，例如：深圳；为行程查时填，用来对比两地天气", required = false) String from) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = parse(startDate);
        LocalDate end = parse(endDate);
        if (start != null && start.isAfter(today.plusDays(FORECAST_DAYS - 1))) {
            return Map.of("outOfRange", true, "hint", "出发日 " + start + " 超出天气预报范围（只能看到今天起 7 天），没有预报。"
                    + "按目的地这个季节的往年情况给穿衣和安排建议"
                    + (from == null || from.isBlank() ? "" : "，并和出发地（" + from.trim() + "）这个季节的往年气温对比，说该多带还是少带衣服")
                    + "，说明是往年情况、不是预报；告诉用户出发前一周可以再按实时天气看一遍。");
        }
        WeatherProvider provider = weatherService.current();
        WeatherProvider.WeatherLocation loc = provider.resolve(location);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", provider.name());
        out.put("location", loc);
        if (start == null) {
            out.put("daily", provider.daily(loc, Math.max(1, Math.min(days == null ? 3 : days, FORECAST_DAYS))));
        } else {
            LocalDate last = end == null || end.isBefore(start) ? start : end;
            List<Map<String, Object>> trip = provider.daily(loc, FORECAST_DAYS).stream()
                    .filter(d -> inRange(d.date(), start, last))
                    .map(WeatherTools::flagged)
                    .toList();
            out.put("daily", trip);
            if (last.isAfter(today.plusDays(FORECAST_DAYS - 1))) {
                out.put("note", today.plusDays(FORECAST_DAYS - 1) + " 以后的几天还没有预报，那几天按往年情况说、别编");
            }
            if (from != null && !from.isBlank() && !trip.isEmpty()) {
                fromWeather(provider, from.trim(), start, last, trip, out);
            }
        }
        out.put("warnings", safeWarnings(provider, loc));
        return out;
    }

    /**
     * 出发地在出发、回来那两天的天气，和两地温差（目的地出行这几天的平均最高、最低温，减去出发地出发那天的）。
     * 查不到出发地不影响目的地的结果。
     */
    private void fromWeather(WeatherProvider provider, String from, LocalDate start, LocalDate last,
                             List<Map<String, Object>> trip, Map<String, Object> out) {
        try {
            List<WeatherProvider.DailyForecast> days = provider.daily(provider.resolve(from), FORECAST_DAYS);
            List<Map<String, Object>> fromDays = days.stream()
                    .filter(d -> start.toString().equals(d.date()) || last.toString().equals(d.date()))
                    .map(WeatherTools::flagged).toList();
            out.put("fromDays", fromDays);
            WeatherProvider.DailyForecast home = days.stream().filter(d -> start.toString().equals(d.date())).findFirst().orElse(null);
            if (home == null) {
                return;
            }
            double max = trip.stream().mapToInt(m -> ((WeatherProvider.DailyForecast) m.get("forecast")).tempMax()).average().orElse(0);
            double min = trip.stream().mapToInt(m -> ((WeatherProvider.DailyForecast) m.get("forecast")).tempMin()).average().orElse(0);
            long dMax = Math.round(max - home.tempMax());
            long dMin = Math.round(min - home.tempMin());
            out.put("compare", "目的地这几天白天最高比" + from + "出发那天" + diff(dMax) + "，夜里最低" + diff(dMin));
        } catch (RuntimeException e) {
            out.put("fromDays", "出发地天气查不到，只按目的地说");
        }
    }

    private static String diff(long d) {
        return d == 0 ? "差不多" : d > 0 ? "高 " + d + "°C" : "低 " + (-d) + "°C";
    }

    /** 一天的预报加上 rainy、hot 两个标记。 */
    private static Map<String, Object> flagged(WeatherProvider.DailyForecast d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forecast", d);
        String text = d.textDay() + d.textNight();
        m.put("rainy", text.contains("雨") || text.contains("雪") || d.precipMm() >= 1);
        m.put("hot", d.tempMax() >= HOT);
        return m;
    }

    private static boolean inRange(String date, LocalDate start, LocalDate end) {
        LocalDate d = parse(date);
        return d != null && !d.isBefore(start) && !d.isAfter(end);
    }

    private static LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim().substring(0, Math.min(10, date.trim().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 逐小时预报附带当前预警，预警查询失败不影响结果。 */
    @Tool(description = "查询某地未来 24 小时的逐小时天气，适合判断爬山、跑步当天几点下雨或起风。")
    public Map<String, Object> hourlyWeather(
            @ToolParam(description = "地名或 经度,纬度") String location) {
        WeatherProvider provider = weatherService.current();
        WeatherProvider.WeatherLocation loc = provider.resolve(location);
        return Map.of("source", provider.name(), "location", loc, "hourly", provider.hourly(loc),
                "warnings", safeWarnings(provider, loc));
    }

    /** 查预警但不抛异常：失败或为空时返回给模型看的提示文字，保证预报结果照常返回。 */
    private Object safeWarnings(WeatherProvider provider, WeatherProvider.WeatherLocation loc) {
        try {
            List<WeatherProvider.WeatherWarning> warnings = provider.warnings(loc);
            return warnings.isEmpty() ? "当前没有预警" : warnings;
        } catch (BizException e) {
            return "预警信息查不到（" + e.getMessage() + "），用户没问预警就不用提";
        }
    }
}
