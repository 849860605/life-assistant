package com.lifeassistant.core.weather;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.cache.QueryCache;
import com.lifeassistant.core.card.CardHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 天气页的数据：实况、空气质量、逐小时、逐日、预警、周边城市，以及按天气数据算出来的出行建议（规则生成，不经过模型）。
 * 数据源不支持的部分（比如高德没有逐小时和空气质量）留空，并在 missing 里说明。整份结果缓存 30 分钟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherBoard {

    private static final int DAYS = 7;
    private static final int NEARBY = 4;

    private final WeatherService weatherService;
    private final AmapWeatherProvider amap;
    private final QueryCache queryCache;

    /** 一条出行建议：kind 是 穿衣、防雨、防晒、交通、空气。 */
    public record Tip(String kind, String text) {
    }

    /** 周边城市今天的天气。 */
    public record NearbyCity(String name, String text, int tempMin, int tempMax) {
    }

    /**
     * 天气页数据：source 是数据源名称；now、air 拿不到为空；hourly、warnings、nearby 拿不到为空列表；
     * missing 是缺了哪些数据和原因；queriedAt 是查询时间。
     */
    public record Board(String source, String city, String adm, double lon, double lat, WeatherProvider.WeatherNow now,
                        WeatherProvider.AirQuality air, List<WeatherProvider.HourlyForecast> hourly,
                        List<WeatherProvider.DailyForecast> daily, List<WeatherProvider.WeatherWarning> warnings,
                        List<NearbyCity> nearby, List<Tip> tips, List<String> missing, String queriedAt) {
    }

    public Board board(String city, boolean refresh) {
        return queryCache.get("weather-board", city.trim(), Duration.ofMinutes(30), refresh, Board.class,
                () -> load(city.trim()), b -> !b.daily().isEmpty());
    }

    /** 某个城市的逐日预报（行程联动用），缓存 30 分钟。 */
    public List<WeatherProvider.DailyForecast> daily(String city, boolean refresh) {
        return queryCache.get("weather-daily", city.trim(), Duration.ofMinutes(30), refresh,
                queryCache.listOf(WeatherProvider.DailyForecast.class), () -> {
                    WeatherProvider p = weatherService.current();
                    return p.daily(p.resolve(city.trim()), DAYS);
                }, list -> !list.isEmpty());
    }

    private Board load(String city) {
        WeatherProvider p = weatherService.current();
        WeatherProvider.WeatherLocation loc = p.resolve(city);
        List<String> missing = new ArrayList<>();
        WeatherProvider.WeatherNow now = optional(() -> p.now(loc), "实况", missing);
        WeatherProvider.AirQuality air = optional(() -> p.air(loc), "空气质量", missing);
        List<WeatherProvider.HourlyForecast> hourly = listOrEmpty(() -> p.hourly(loc), "逐小时预报", missing);
        List<WeatherProvider.DailyForecast> daily = p.daily(loc, DAYS);
        if (daily.size() < DAYS) {
            missing.add(p.name() + "只给 " + daily.size() + " 天预报");
        }
        List<WeatherProvider.WeatherWarning> warnings = listOrEmpty(() -> p.warnings(loc), "天气预警", missing);
        List<NearbyCity> nearby = nearby(p, loc, missing);
        return new Board(p.name(), loc.name(), loc.adm(), loc.lon(), loc.lat(), now, air, hourly, daily, warnings,
                nearby, tips(now, air, hourly, daily), missing, CardHub.now());
    }

    /** 同省最近的几个城市今天的天气；城市列表来自高德行政区划。 */
    private List<NearbyCity> nearby(WeatherProvider p, WeatherProvider.WeatherLocation loc, List<String> missing) {
        if (!amap.available()) {
            missing.add("周边城市（要配高德 Key）");
            return List.of();
        }
        List<NearbyCity> out = new ArrayList<>();
        try {
            for (WeatherProvider.WeatherLocation c : amap.nearbyCities(loc, NEARBY)) {
                // 和风用坐标查，高德直接用行政区划编码
                WeatherProvider.WeatherLocation at = p == amap ? c : p.resolve(c.lon() + "," + c.lat());
                List<WeatherProvider.DailyForecast> d = p.daily(at, 1);
                if (!d.isEmpty()) {
                    out.add(new NearbyCity(c.name(), d.get(0).textDay(), d.get(0).tempMin(), d.get(0).tempMax()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("查周边城市天气失败：{}", e.getMessage());
            missing.add("周边城市（这次没查到）");
        }
        return out;
    }

    /**
     * 出行建议，全部按天气数据的规则算：今天的温差和最高最低温决定穿衣，今明两天和逐小时的降水决定带伞，
     * 紫外线（和风才有）决定防晒，雨雪雾霾提醒交通，空气质量超过 100 提醒口罩。
     */
    static List<Tip> tips(WeatherProvider.WeatherNow now, WeatherProvider.AirQuality air,
                          List<WeatherProvider.HourlyForecast> hourly, List<WeatherProvider.DailyForecast> daily) {
        List<Tip> tips = new ArrayList<>();
        if (daily.isEmpty()) {
            return tips;
        }
        WeatherProvider.DailyForecast today = daily.get(0);
        int diff = today.tempMax() - today.tempMin();
        String wear = today.tempMin() <= 0 ? "最低 " + today.tempMin() + "°C，穿羽绒服或厚外套，注意保暖"
                : today.tempMin() <= 8 ? "最低 " + today.tempMin() + "°C，穿厚外套或毛衣"
                : today.tempMax() >= 30 ? "最高 " + today.tempMax() + "°C，穿短袖等透气衣物，多补水"
                : today.tempMax() >= 22 ? "最高 " + today.tempMax() + "°C，长袖或短袖都可以"
                : "气温 " + today.tempMin() + "～" + today.tempMax() + "°C，长袖加薄外套";
        if (diff >= 10) {
            wear += "；昼夜温差 " + diff + "°C，早晚带件外套";
        }
        tips.add(new Tip("穿衣", wear));

        List<String> rainy = new ArrayList<>();
        for (int i = 0; i < Math.min(2, daily.size()); i++) {
            WeatherProvider.DailyForecast d = daily.get(i);
            if (wet(d.textDay()) || wet(d.textNight()) || d.precipMm() >= 1) {
                rainy.add((i == 0 ? "今天" : "明天") + d.textDay());
            }
        }
        List<String> rainHours = hourly.stream().limit(12).filter(h -> h.pop() >= 50 || wet(h.text()))
                .map(h -> h.time().length() >= 16 ? h.time().substring(11, 16) : h.time()).toList();
        if (!rainHours.isEmpty()) {
            tips.add(new Tip("防雨", "接下来 " + rainHours.get(0) + " 起可能下雨，出门带伞" + (rainy.isEmpty() ? "" : "（" + String.join("，", rainy) + "）")));
        } else if (!rainy.isEmpty()) {
            tips.add(new Tip("防雨", String.join("，", rainy) + "，出门带伞，户外景点可以和室内的换一换"));
        } else {
            tips.add(new Tip("防雨", "今明两天预报没有雨，适合户外游览"));
        }

        Integer uv = parseInt(today.uvIndex());
        if (uv != null) {
            tips.add(new Tip("防晒", uv >= 8 ? "紫外线很强（" + uv + "），尽量避开中午暴晒，涂防晒、戴帽子"
                    : uv >= 5 ? "紫外线较强（" + uv + "），建议涂防晒、戴太阳镜"
                    : "紫外线不强（" + uv + "），一般不用特别防晒"));
        } else if (today.textDay().contains("晴")) {
            tips.add(new Tip("防晒", "白天晴，户外时间长的话注意防晒（这个数据源没有紫外线指数）"));
        }

        String text = (now == null ? "" : now.text()) + today.textDay();
        tips.add(new Tip("交通", wet(text) ? "有雨雪，路面湿滑，打车和步行都多留点时间"
                : text.matches(".*(雾|霾|沙|尘).*") ? "有雾或霾，能见度低，航班和高速可能受影响，出发前确认"
                : "天气对出行影响不大"));
        if (air != null && air.aqi() != null && air.aqi() > 100) {
            tips.add(new Tip("空气", "空气质量" + (air.category() == null ? "" : air.category()) + "（AQI " + air.aqi()
                    + "），敏感人群减少户外活动，可以戴口罩"));
        }
        return tips;
    }

    /** 雨、雪、雷暴都算会淋湿 */
    public static boolean wet(String text) {
        return text != null && text.matches(".*(雨|雪|雷|冰雹).*");
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> T optional(Supplier<T> load, String what, List<String> missing) {
        try {
            return load.get();
        } catch (BizException e) {
            missing.add(what + "（" + e.getMessage() + "）");
            return null;
        } catch (RuntimeException e) {
            missing.add(what + "（这次没查到）");
            return null;
        }
    }

    private static <T> List<T> listOrEmpty(Supplier<List<T>> load, String what, List<String> missing) {
        List<T> out = optional(load, what, missing);
        return out == null ? List.of() : out;
    }
}
