package com.lifeassistant.core.weather;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 为出行查天气：超出预报范围不调接口；范围内只返回出行那几天，并标好下雨和高温。 */
class WeatherToolsTripTest {

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final WeatherProvider provider = Mockito.mock(WeatherProvider.class);
    private final WeatherTools tools;

    WeatherToolsTripTest() {
        WeatherService service = Mockito.mock(WeatherService.class);
        when(service.current()).thenReturn(provider);
        when(provider.name()).thenReturn("和风天气");
        when(provider.resolve(any())).thenReturn(new WeatherProvider.WeatherLocation("1", "深圳", "广东", 114.06, 22.54));
        // 今天起 7 天：第 2 天小雨，第 3 天 34°C
        when(provider.daily(any(), anyInt())).thenReturn(IntStream.range(0, 7).mapToObj(i -> new WeatherProvider.DailyForecast(
                today.plusDays(i).toString(), i == 1 ? "小雨" : "晴", "多云", 22, i == 2 ? 34 : 28, "3", i == 1 ? 6.2 : 0,
                "5", "06:30", "18:00")).toList());
        tools = new WeatherTools(service);
    }

    @Test
    void farTripDoesNotCallWeatherApi() {
        Map<String, Object> r = tools.dailyWeather("深圳", 3, today.plusDays(10).toString(), today.plusDays(12).toString(), "广州");
        assertThat(r.get("outOfRange")).isEqualTo(true);
        assertThat((String) r.get("hint")).contains("往年").contains("广州");
        verify(provider, never()).resolve(any());
        verify(provider, never()).daily(any(), anyInt());
    }

    @Test
    void nearTripReturnsOnlyTripDaysWithFlags() {
        Map<String, Object> r = tools.dailyWeather("深圳", 7, today.plusDays(1).toString(), today.plusDays(2).toString(), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("daily");
        assertThat(days).hasSize(2);
        assertThat(days.get(0).get("rainy")).isEqualTo(true);
        assertThat(days.get(0).get("hot")).isEqualTo(false);
        assertThat(days.get(1).get("rainy")).isEqualTo(false);
        assertThat(days.get(1).get("hot")).isEqualTo(true);
        assertThat(r).doesNotContainKey("note");
    }

    @Test
    void tripRunningPastForecastSaysSo() {
        Map<String, Object> r = tools.dailyWeather("深圳", 7, today.plusDays(5).toString(), today.plusDays(9).toString(), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("daily");
        assertThat(days).hasSize(2);
        assertThat((String) r.get("note")).contains("还没有预报");
    }

    @Test
    void comparesWithOriginCity() {
        // 同一份假数据：出发地出发那天最高 34（第 3 天），目的地这两天平均最高 31
        Map<String, Object> r = tools.dailyWeather("广州", 7, today.plusDays(2).toString(), today.plusDays(3).toString(), "深圳");
        assertThat(r).containsKey("fromDays");
        assertThat((String) r.get("compare")).contains("白天最高比深圳出发那天低 3°C").contains("夜里最低差不多");
    }
}
