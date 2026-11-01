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
 * 天气接口：页面顶栏显示某个城市今天的天气；天气页的完整数据；行程每天的天气和下雨天的对调建议。
 */
@RestController
@RequestMapping("/api/v1/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;
    private final com.lifeassistant.core.weather.WeatherBoard weatherBoard;
    private final com.lifeassistant.skill.travel.trip.TripWeather tripWeather;
    private final com.lifeassistant.core.auth.AuthFacade authFacade;

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

    /**
     * GET /api/v1/weather/board?city=西安&refresh=false：天气页数据（实况、空气质量、逐小时、逐日、预警、周边城市、
     * 出行建议），数据源不支持的部分留空并在 missing 里说明；缓存 30 分钟。
     */
    @GetMapping("/board")
    public ApiResponse<com.lifeassistant.core.weather.WeatherBoard.Board> board(@RequestParam String city,
                                                                               @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(weatherBoard.board(city, refresh));
    }

    /** GET /api/v1/weather/trip/{id}：行程每天的天气（在预报范围内的）和下雨天的对调建议。 */
    @GetMapping("/trip/{id}")
    public ApiResponse<com.lifeassistant.skill.travel.trip.TripWeather.TripForecast> trip(
            @org.springframework.web.bind.annotation.PathVariable long id,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(tripWeather.forecast(authFacade.current(), id, refresh));
    }
}
