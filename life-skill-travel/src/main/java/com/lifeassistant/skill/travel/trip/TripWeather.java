package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.weather.WeatherBoard;
import com.lifeassistant.core.weather.WeatherProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行程和天气联动：行程每天的预报（在预报范围内的），以及"下雨天的户外景点和别的晴天的室内景点对调"的建议。
 * 建议只是指出哪两项，真正的路线对比由 {@link TripEditor#previewSwap} 按高德算，用户确认才保存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripWeather {

    private final TripService tripService;
    private final TripEditor editor;
    private final WeatherBoard weatherBoard;

    /** 行程某天的天气：forecast 为空表示不在预报范围内；rainy 是白天或夜里有雨雪。 */
    public record DayWeather(int day, String date, WeatherProvider.DailyForecast forecast, boolean rainy) {
    }

    /**
     * 对调建议：把下雨那天（rainDay）的户外景点和晴天（dryDay）的室内景点对调。outdoorId、indoorId 是两项的 id，
     * text 是给用户看的一句话。
     */
    public record Suggestion(int rainDay, String outdoorId, String outdoorName, int dryDay, String indoorId,
                             String indoorName, String text) {
    }

    /** 行程天气：city 是查天气用的城市，days 是每天的天气，suggestion 没有合适对调时为空，note 是说明。 */
    public record TripForecast(long tripId, String city, List<DayWeather> days, Suggestion suggestion, String note) {
    }

    public TripForecast forecast(UserCtx ctx, long tripId, boolean refresh) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = editor.itinerary(detail);
        String city = cityOf(detail.trip().getDestination());
        Map<String, WeatherProvider.DailyForecast> byDate = weatherBoard.daily(city, refresh).stream()
                .collect(Collectors.toMap(WeatherProvider.DailyForecast::date, Function.identity(), (x, y) -> x));
        List<DayWeather> days = new ArrayList<>();
        for (int i = 0; i < itinerary.days().size(); i++) {
            String date = itinerary.days().get(i).date();
            WeatherProvider.DailyForecast f = byDate.get(date);
            days.add(new DayWeather(i + 1, date, f, f != null && rainy(f)));
        }
        Suggestion s = suggest(itinerary, days);
        String note = days.stream().noneMatch(d -> d.forecast() != null) ? "行程日期还不在天气预报范围内，临近出发再看"
                : s == null && days.stream().anyMatch(DayWeather::rainy) ? "有雨的那天没找到可以和晴天室内景点对调的户外景点"
                : null;
        return new TripForecast(tripId, city, days, s, note);
    }

    /** 预报里白天、夜里有雨雪，或降水量 ≥ 1 毫米 */
    static boolean rainy(WeatherProvider.DailyForecast f) {
        return WeatherBoard.wet(f.textDay()) || WeatherBoard.wet(f.textNight()) || f.precipMm() >= 1;
    }

    /**
     * 找第一个下雨天里能挪的户外景点（没锁定、没预约），再在预报不下雨的另一天里找能挪的室内景点；
     * 优先选和下雨天户外景点时长接近的，免得对调后时间段放不下。
     */
    static Suggestion suggest(Itinerary itinerary, List<DayWeather> days) {
        for (DayWeather rain : days) {
            if (!rain.rainy()) {
                continue;
            }
            List<Itinerary.Item> outdoors = movable(itinerary, rain.day() - 1, false);
            if (outdoors.isEmpty()) {
                continue;
            }
            for (DayWeather dry : days) {
                if (dry.day() == rain.day() || dry.forecast() == null || dry.rainy()) {
                    continue;
                }
                List<Itinerary.Item> indoors = movable(itinerary, dry.day() - 1, true);
                if (indoors.isEmpty()) {
                    continue;
                }
                Itinerary.Item out = outdoors.get(0);
                Itinerary.Item in = indoors.stream().min(java.util.Comparator.comparingInt(x ->
                        Math.abs(minutes(x) - minutes(out)))).orElseThrow();
                String text = "第 " + rain.day() + " 天（" + rain.date().substring(5) + "）预报" + rain.forecast().textDay()
                        + "，建议把户外的「" + out.name() + "」和第 " + dry.day() + " 天的「" + in.name() + "」对调";
                return new Suggestion(rain.day(), out.id(), out.name(), dry.day(), in.id(), in.name(), text);
            }
        }
        return null;
    }

    /** 某天能挪的景点：没锁定、没预约，室内或户外。 */
    private static List<Itinerary.Item> movable(Itinerary itinerary, int dayIndex, boolean indoor) {
        return itinerary.days().get(dayIndex).items().stream()
                .filter(i -> "SPOT".equals(i.type()) && !Boolean.TRUE.equals(i.locked()) && !itinerary.isBooked(i.id()))
                .filter(i -> TripEditor.indoor(i) == indoor)
                .toList();
    }

    private static int minutes(Itinerary.Item i) {
        try {
            if (i.start() == null || i.end() == null) {
                return 120;
            }
            return (int) java.time.Duration.between(java.time.LocalTime.parse(pad(i.start())),
                    java.time.LocalTime.parse(pad(i.end()))).toMinutes();
        } catch (RuntimeException e) {
            return 120;
        }
    }

    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    /** 目的地取城市部分："西安·兵马俑" → "西安"。 */
    private static String cityOf(String destination) {
        return destination == null ? "" : destination.split("[·・\\s,，]")[0];
    }
}
