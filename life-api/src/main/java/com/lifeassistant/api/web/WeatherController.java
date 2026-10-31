package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.weather.WeatherProvider;
import com.lifeassistant.core.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 天气接口：页面顶栏显示某个城市今天的天气。
 */
@RestController
@RequestMapping("/api/v1/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /** 今天的天气：城市名、白天天气、最低和最高气温。 */
    public record Today(String city, String text, int tempMin, int tempMax) {
    }

    /** GET /api/v1/weather/today?city=西安：今天的天气；没配置天气数据源时报错。 */
    @GetMapping("/today")
    public ApiResponse<Today> today(@RequestParam String city) {
        WeatherProvider provider = weatherService.current();
        WeatherProvider.WeatherLocation location = provider.resolve(city);
        List<WeatherProvider.DailyForecast> daily = provider.daily(location, 1);
        if (daily.isEmpty()) {
            throw BizException.notFound("天气");
        }
        WeatherProvider.DailyForecast d = daily.get(0);
        return ApiResponse.ok(new Today(location.name(), d.textDay(), d.tempMin(), d.tempMax()));
    }
}
