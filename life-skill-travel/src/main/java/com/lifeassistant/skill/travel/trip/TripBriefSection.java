package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.brief.Brief;
import com.lifeassistant.core.brief.BriefSection;
import com.lifeassistant.core.brief.WeatherBriefSection;
import com.lifeassistant.core.weather.WeatherBoard;
import com.lifeassistant.core.weather.WeatherProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 简报的行程段：旅行中报今天第几天、当地天气和今天的安排；7 天内要出发的报倒计时、目的地天气和还没订的机票酒店，
 * 出发前一天提醒第一程几点。最多两个行程，已确认的优先。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripBriefSection implements BriefSection {

    static final int AHEAD_DAYS = 7;
    private static final int MAX_ITEMS = 4;
    private static final Pattern FLIGHT = Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{2}\\d{3,4}(?!\\d)|航班|机场");

    private final TripService tripService;
    private final WeatherBoard weather;
    private final ObjectMapper objectMapper;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Brief.Section> build(Brief.Context context) {
        LocalDate today = context.today();
        UserCtx ctx = UserCtx.of(context.userId());
        List<Trip> trips = tripService.list(ctx).stream()
                .filter(t -> t.getStartDate() != null && t.getEndDate() != null)
                .filter(t -> !t.getEndDate().isBefore(today) && !t.getStartDate().isAfter(today.plusDays(AHEAD_DAYS)))
                .sorted(Comparator.comparing(Trip::getStartDate)
                        .thenComparing(t -> "CONFIRMED".equals(t.getStatus()) ? 0 : 1))
                .limit(2).toList();
        if (trips.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        boolean away = false;
        for (Trip trip : trips) {
            Itinerary it = itinerary(ctx, trip);
            String name = "「" + trip.getTitle() + "」" + ("CONFIRMED".equals(trip.getStatus()) ? "" : "（草稿）");
            if (!trip.getStartDate().isAfter(today)) {
                away = true;
                ongoing(trip, it, today, name, lines);
            } else {
                upcoming(trip, it, today, name, lines);
            }
        }
        if (away) {
            context.markAway();
        }
        return Optional.of(new Brief.Section("trip", away ? "旅行中" : "行程", lines,
                "/pages/trips/detail?id=" + trips.get(0).getId()));
    }

    /** 旅行中：第几天、当地天气、今天的前几项安排。 */
    private void ongoing(Trip trip, Itinerary it, LocalDate today, String name, List<String> lines) {
        long total = ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1;
        long n = ChronoUnit.DAYS.between(trip.getStartDate(), today) + 1;
        Itinerary.Day day = day(it, today);
        lines.add(name + "第 " + n + "/" + total + " 天" + (n == total ? "，今天返程" : "")
                + (day != null && day.theme() != null ? "：" + day.theme() : ""));
        weatherLine(trip, day, today, false).ifPresent(lines::add);
        if (day == null) {
            return;
        }
        List<Itinerary.Item> items = day.items().stream().filter(i -> i.start() != null && !i.start().isBlank()).toList();
        items.stream().limit(MAX_ITEMS).forEach(i -> lines.add(i.start() + " " + i.name()
                + (it.isBooked(i.id()) ? "（已预约）" : "")));
        if (items.size() > MAX_ITEMS) {
            lines.add("今天一共 " + items.size() + " 项，打开行程看全部");
        }
    }

    /** 还没出发：倒计时、目的地天气（预报覆盖到才有）、没订的机票酒店，出发前一天提醒第一程。 */
    private void upcoming(Trip trip, Itinerary it, LocalDate today, String name, List<String> lines) {
        long days = ChronoUnit.DAYS.between(today, trip.getStartDate());
        lines.add((days == 1 ? "明天出发：" : "还有 " + days + " 天出发：") + name
                + "（" + trip.getStartDate().getMonthValue() + "/" + trip.getStartDate().getDayOfMonth() + "）");
        Itinerary.Day first = day(it, trip.getStartDate());
        weatherLine(trip, first, trip.getStartDate(), true).ifPresent(lines::add);
        if (it == null) {
            return;
        }
        if (days == 1 && first != null) {
            // 已预约的交通优先（用户换过航班时，旧的那项可能还留着）
            first.items().stream().filter(i -> "TRANSPORT".equals(i.type()) && i.start() != null && !i.start().isBlank())
                    .min(Comparator.comparing((Itinerary.Item i) -> it.isBooked(i.id()) ? 0 : 1).thenComparing(Itinerary.Item::start))
                    .ifPresent(i -> lines.add("明天 " + i.start() + " " + i.name()
                            + (FLIGHT.matcher(i.name() + " " + (i.note() == null ? "" : i.note())).find()
                            ? "，国内航班建议提前 1.5–2 小时到机场" : "")));
        }
        List<String> todo = new ArrayList<>();
        // 按天看：有航班的那天一班都没标已预约，才算没订
        for (int d = 0; d < it.days().size(); d++) {
            List<Itinerary.Item> flights = it.days().get(d).items().stream()
                    .filter(i -> "TRANSPORT".equals(i.type()) && i.name() != null && FLIGHT.matcher(i.name()).find()).toList();
            if (!flights.isEmpty() && flights.stream().noneMatch(i -> it.isBooked(i.id()))) {
                todo.add(d == 0 ? "去程航班" : d == it.days().size() - 1 ? "返程航班" : "第 " + (d + 1) + " 天的航班");
            }
        }
        (it.stays() == null ? List.<Itinerary.Stay>of() : it.stays()).stream().filter(s -> !it.isBooked(s.id()))
                .map(s -> "酒店「" + s.name() + "」").distinct().forEach(todo::add);
        if (!todo.isEmpty()) {
            lines.add("还没标已预约：" + String.join("、", todo));
        }
    }

    /** 某天在行程目的地的天气：先按目的地名查，查不到用当天第一个有坐标的地点。预报没覆盖到这天返回空；showDate 时带上日期。 */
    private Optional<String> weatherLine(Trip trip, Itinerary.Day day, LocalDate date, boolean showDate) {
        List<String> queries = new ArrayList<>();
        String city = cityOf(trip.getDestination());
        if (city != null) {
            queries.add(city);
        }
        if (day != null) {
            day.items().stream().filter(Itinerary.Item::located).findFirst()
                    .ifPresent(i -> queries.add(i.lng() + "," + i.lat()));
        }
        for (String q : queries) {
            try {
                Optional<WeatherProvider.DailyForecast> d = weather.daily(q, false).stream()
                        .filter(f -> date.toString().equals(f.date())).findFirst();
                if (d.isPresent()) {
                    String label = q.equals(city) ? city : "目的地";
                    return Optional.of(label + (!showDate ? "" : " " + date.getMonthValue() + "/"
                            + date.getDayOfMonth()) + "：" + WeatherBriefSection.line(d.get()));
                }
                return Optional.empty();
            } catch (RuntimeException e) {
                log.debug("简报查 {} 天气失败：{}", q, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** "新疆巴音郭楞（库尔勒·轮台）" → 新疆巴音郭楞；"赤峰" → 赤峰。 */
    static String cityOf(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String name = destination.replaceAll("[(（].*?[)）]", "").split("[·・,，、/\\s]")[0].trim();
        return name.isEmpty() ? null : name;
    }

    private static Itinerary.Day day(Itinerary it, LocalDate date) {
        return it == null ? null : it.days().stream().filter(d -> date.toString().equals(d.date())).findFirst().orElse(null);
    }

    private Itinerary itinerary(UserCtx ctx, Trip trip) {
        try {
            String json = tripService.get(ctx, trip.getId(), null).version().getItinerary();
            return json == null ? null : objectMapper.readValue(json, Itinerary.class);
        } catch (Exception e) {
            return null;
        }
    }
}
