package com.lifeassistant.core.brief;

import com.lifeassistant.core.weather.WeatherBoard;
import com.lifeassistant.core.weather.WeatherProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 简报的天气段：常住城市今天的天气，要下雨提醒带伞，明天变天也提一句。人在外地旅行时由行程段报当地天气。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherBriefSection implements BriefSection {

    private final WeatherBoard weather;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Optional<Brief.Section> build(Brief.Context context) {
        if (context.away()) {
            return Optional.empty();
        }
        if (context.homeCity() == null) {
            return Optional.of(new Brief.Section("weather", "天气",
                    List.of("在「我的 → 每日简报」里设一下城市，就能看到每天的天气"), "/pages/me/me"));
        }
        try {
            List<WeatherProvider.DailyForecast> days = weather.daily(context.homeCity(), false);
            String today = context.today().toString();
            String tomorrow = context.today().plusDays(1).toString();
            List<String> lines = new ArrayList<>();
            days.stream().filter(d -> today.equals(d.date())).findFirst()
                    .ifPresent(d -> lines.add(context.homeCity() + "：" + line(d)));
            days.stream().filter(d -> tomorrow.equals(d.date())).findFirst()
                    .filter(d -> WeatherBoard.wet(d.textDay()) || WeatherBoard.wet(d.textNight()))
                    .ifPresent(d -> lines.add("明天" + d.textDay() + "，" + d.tempMin() + "–" + d.tempMax() + "°"));
            return lines.isEmpty() ? Optional.empty()
                    : Optional.of(new Brief.Section("weather", "天气", lines, "/pages/weather/weather"));
        } catch (RuntimeException e) {
            log.warn("简报查 {} 天气失败：{}", context.homeCity(), e.getMessage());
            return Optional.of(new Brief.Section("weather", "天气", List.of(context.homeCity() + "的天气这会儿没查到"),
                    "/pages/weather/weather"));
        }
    }

    /** 一天的天气写成一句：多云转小雨 22–27°，要带伞。 */
    public static String line(WeatherProvider.DailyForecast d) {
        String text = d.textDay().equals(d.textNight()) ? d.textDay() : d.textDay() + "转" + d.textNight();
        boolean wet = WeatherBoard.wet(d.textDay()) || WeatherBoard.wet(d.textNight());
        return text + " " + d.tempMin() + "–" + d.tempMax() + "°" + (wet ? "，出门带伞" : "")
                + (d.tempMax() - d.tempMin() >= 10 ? "，早晚温差大" : "");
    }
}
