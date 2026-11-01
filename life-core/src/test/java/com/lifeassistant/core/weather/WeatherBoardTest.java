package com.lifeassistant.core.weather;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 出行建议按天气数据的规则生成。 */
class WeatherBoardTest {

    @Test
    void tipsFollowTheForecast() {
        List<WeatherProvider.DailyForecast> daily = List.of(
                new WeatherProvider.DailyForecast("2026-11-20", "晴", "晴", 4, 16, "3", 0, "7", "", ""),
                new WeatherProvider.DailyForecast("2026-11-21", "小雨", "小雨", 6, 12, "3", 3, "2", "", ""));

        List<WeatherBoard.Tip> tips = WeatherBoard.tips(null, null, List.of(), daily);

        assertThat(tips).extracting(WeatherBoard.Tip::kind).contains("穿衣", "防雨", "防晒", "交通");
        assertThat(tips.get(0).text()).contains("厚外套").contains("温差 12°C");
        assertThat(tips).filteredOn(t -> t.kind().equals("防雨")).first().extracting(WeatherBoard.Tip::text)
                .asString().contains("明天小雨");
        assertThat(tips).filteredOn(t -> t.kind().equals("防晒")).first().extracting(WeatherBoard.Tip::text)
                .asString().contains("较强");
    }
}
