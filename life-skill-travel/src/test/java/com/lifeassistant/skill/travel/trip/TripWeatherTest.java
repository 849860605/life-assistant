package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.weather.WeatherProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 下雨天对调建议：雨天的户外景点和晴天的室内景点对调，锁定的不动。 */
class TripWeatherTest {

    private static Itinerary.Item spot(String id, String start, String end, String name, Boolean locked) {
        return new Itinerary.Item(id, start, end, "SPOT", name, null, null, null, null, locked);
    }

    private static WeatherProvider.DailyForecast f(String date, String text) {
        return new WeatherProvider.DailyForecast(date, text, text, 10, 18, "3", 0, "", "", "");
    }

    @Test
    void swapOutdoorOnRainyDayWithIndoorOnDryDay() {
        Itinerary it = new Itinerary(List.of(
                new Itinerary.Day("2026-11-21", null, List.of(spot("d1-1", "09:00", "12:00", "西安城墙", null))),
                new Itinerary.Day("2026-11-22", null, List.of(
                        spot("d2-1", "09:00", "11:00", "大雁塔", null),
                        spot("d2-2", "14:00", "17:00", "陕西历史博物馆", null)))), null);
        List<TripWeather.DayWeather> days = List.of(
                new TripWeather.DayWeather(1, "2026-11-21", f("2026-11-21", "小雨"), true),
                new TripWeather.DayWeather(2, "2026-11-22", f("2026-11-22", "晴"), false));

        TripWeather.Suggestion s = TripWeather.suggest(it, days);

        assertThat(s).isNotNull();
        assertThat(s.outdoorName()).isEqualTo("西安城墙");
        assertThat(s.indoorName()).isEqualTo("陕西历史博物馆");
        assertThat(s.dryDay()).isEqualTo(2);
    }

    @Test
    void lockedOutdoorIsNotMoved() {
        Itinerary it = new Itinerary(List.of(
                new Itinerary.Day("2026-11-21", null, List.of(spot("d1-1", "09:00", "12:00", "西安城墙", true))),
                new Itinerary.Day("2026-11-22", null, List.of(spot("d2-1", "14:00", "17:00", "西安博物院", null)))), null);
        List<TripWeather.DayWeather> days = List.of(
                new TripWeather.DayWeather(1, "2026-11-21", f("2026-11-21", "中雨"), true),
                new TripWeather.DayWeather(2, "2026-11-22", f("2026-11-22", "多云"), false));

        assertThat(TripWeather.suggest(it, days)).isNull();
        assertThat(TripEditor.indoor(spot("x", null, null, "西安博物院 · 小雁塔", null))).isTrue();
        assertThat(TripEditor.indoor(spot("x", null, null, "大唐不夜城步行街", null))).isFalse();
    }
}
