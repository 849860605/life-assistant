package com.lifeassistant.skill.travel.today;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.memory.MemoryCategory;
import com.lifeassistant.core.memory.MemoryItem;
import com.lifeassistant.core.memory.MemoryService;
import com.lifeassistant.core.memory.MemorySource;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.schedule.ScheduleService;
import com.lifeassistant.core.user.UserService;
import com.lifeassistant.skill.travel.trip.Itinerary;
import com.lifeassistant.skill.travel.trip.Trip;
import com.lifeassistant.skill.travel.trip.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 首页的"今日安排"：今天的日程，加上进行中旅行当天的安排，按时间排；每项带坐标（导航、打车用），
 * 还没开始的几项按高德驾车路线估"几点出发、路上多久、打车约多少钱"。
 * 出发地：旅行中是当晚住的酒店（行程里相邻两项的路程直接用行程算好的），平时是"我的"里设的家。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodayAgenda {

    /** 家的地址存在记忆里（星络对话时也知道），这是记忆的 key */
    public static final String HOME_KEY = "address:home";
    private static final String HOME_PREFIX = "家的地址：";
    /** 建议出发时间在路上时间之外再留这么多分钟 */
    static final int BUFFER_MINUTES = 10;
    /** 最多给这么多项估路程（每项一次高德路线查询） */
    private static final int MAX_ROUTES = 3;
    private static final Set<String> TRIP_TYPES = Set.of("SPOT", "FOOD", "HOTEL", "TRANSPORT");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ScheduleService scheduleService;
    private final TripService tripService;
    private final MemoryService memoryService;
    private final AmapPlaceService places;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    /**
     * 一项安排：kind 是 EVENT（日程）或 TRIP（行程里的一项）；time、end 是 HH:mm（全天的 time 为"全天"）；
     * place 是地点名，lng、lat 是高德坐标（找不到为空，前端就不显示导航）；minutes、taxi 是从出发地过去的车程和打车费估算，
     * from 是从哪出发（"家"、酒店名、上一站名），mode 是这段路怎么走（DRIVE、WALK、TRANSIT），leaveAt 是建议出发时间；
 * past 表示已经过了开始时间。
     * tripId 只有行程项有，点进去看行程。
     */
    public record Entry(String kind, String id, String time, String end, String title, String place, String note,
                        String type, Double lng, Double lat, Integer minutes, Integer taxi, String from,
                        String mode, String leaveAt, boolean past, Long tripId) {
    }

    /** 今天在旅行中：哪个行程、第几天、一共几天。 */
    public record TripToday(long id, String title, int dayNo, int totalDays) {
    }

    /** 今日安排：日期、按时间排的各项、进行中的旅行（没有为空）、平时用的出发地（家，没设为空）。 */
    public record Agenda(String date, List<Entry> entries, TripToday trip, String home) {
    }

    /** 今天的安排；city 是页面当前城市，用来在高德里找日程地点。 */
    public Agenda today(UserCtx ctx, String city) {
        ZoneId zone = userService.zoneOf(ctx.userId());
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        String home = homeAddress(ctx);
        double[] homePoint = home == null ? null : places.center(home).orElse(null);

        Optional<Ongoing> ongoing = ongoing(ctx, today);
        String searchCity = ongoing.map(o -> o.trip().getDestination()).filter(StringUtils::hasText).orElse(city);
        List<Entry> entries = new ArrayList<>();
        int routes = 0;

        List<ScheduleEvent> events = scheduleService.list(ctx, today.atStartOfDay(zone).toOffsetDateTime(),
                today.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        for (ScheduleEvent e : events) {
            // 旅行中，行程建的提醒和行程安排重复，只列行程安排
            if (ongoing.isPresent() && TripService.SCHEDULE_SOURCE.equals(e.getSource())) {
                continue;
            }
            boolean allDay = Boolean.TRUE.equals(e.getAllDay());
            LocalTime start = e.getStartAt().atZoneSameInstant(zone).toLocalTime();
            String end = e.getEndAt() == null || allDay ? null : e.getEndAt().atZoneSameInstant(zone).format(HM);
            boolean past = !allDay && start.isBefore(now.toLocalTime());
            double[] point = locate(e.getLocation(), searchCity);
            Integer minutes = null;
            Integer taxi = null;
            String leaveAt = null;
            // 旅行中从酒店出发，平时从家出发
            double[] origin = ongoing.map(Ongoing::stayPoint).orElse(homePoint);
            String from = ongoing.isPresent() ? ongoing.get().stayName() : homePoint == null ? null : "家";
            if (!past && !allDay && point != null && origin != null && routes < MAX_ROUTES) {
                routes++;
                Optional<AmapPlaceService.Route> route = route(origin, point);
                if (route.isPresent()) {
                    minutes = route.get().minutes();
                    taxi = route.get().taxi();
                    leaveAt = start.minusMinutes(minutes + BUFFER_MINUTES).format(HM);
                }
            }
            entries.add(new Entry("EVENT", String.valueOf(e.getId()), allDay ? "全天" : start.format(HM), end,
                    e.getTitle(), e.getLocation(), e.getDescription(), null, point == null ? null : point[0],
                    point == null ? null : point[1], minutes, taxi, minutes == null ? null : from,
                    minutes == null ? null : Itinerary.DRIVE, leaveAt, past, null));
        }

        TripToday tripToday = null;
        if (ongoing.isPresent()) {
            Ongoing o = ongoing.get();
            tripToday = new TripToday(o.trip().getId(), o.trip().getTitle(), o.dayNo(), o.totalDays());
            Itinerary.Item previous = null;
            for (Itinerary.Item item : o.day().items()) {
                if (!TRIP_TYPES.contains(item.type()) || item.start() == null) {
                    continue;
                }
                LocalTime start = parseTime(item.start());
                boolean past = start != null && start.isBefore(now.toLocalTime());
                Itinerary.Leg leg = previous == null || o.day().legs() == null ? null : o.day().legs().stream()
                        .filter(l -> item.id().equals(l.to())).findFirst().orElse(null);
                Integer minutes = leg == null ? null : leg.minutes();
                String leaveAt = minutes == null || start == null ? null : start.minusMinutes(minutes).format(HM);
                entries.add(new Entry("TRIP", item.id(), item.start(), item.end(), item.name(), item.address(),
                        item.note(), item.type(), item.lng(), item.lat(), minutes, leg == null ? null : leg.taxi(),
                        minutes == null ? null : previous.name(), leg == null ? null : leg.mode(), past ? null : leaveAt,
                        past, o.trip().getId()));
                previous = item;
            }
        }
        entries.sort(Comparator.comparing((Entry x) -> "全天".equals(x.time()) ? "" : x.time()));
        return new Agenda(today.toString(), entries, tripToday, home);
    }

    /** 读"我的"里设的家的地址，没设返回空。 */
    public String homeAddress(UserCtx ctx) {
        return memoryService.listForOwner(ctx).stream()
                .filter(m -> HOME_KEY.equals(m.getMemKey()) && m.getContent() != null)
                .map(MemoryItem::getContent)
                .map(c -> c.startsWith(HOME_PREFIX) ? c.substring(HOME_PREFIX.length()) : c)
                .map(String::trim).filter(StringUtils::hasText).findFirst().orElse(null);
    }

    /** 设置家的地址（空表示删掉）；先在高德里找一下，找不到坐标就不存，提示换个写法。 */
    public String saveHome(UserCtx ctx, String address) {
        String text = address == null ? "" : address.trim();
        if (text.isEmpty()) {
            memoryService.deleteByKey(ctx, MemoryCategory.PROFILE, HOME_KEY);
            return null;
        }
        if (text.length() > 120) {
            text = text.substring(0, 120);
        }
        if (places.center(text).isEmpty()) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.BAD_REQUEST,
                    "高德上没找到这个地址，写得具体一点，比如带上城市和小区名");
        }
        memoryService.upsert(ctx, MemoryCategory.PROFILE, HOME_KEY, HOME_PREFIX + text, MemorySource.USER_STATED, null);
        return text;
    }

    /** 今天正在进行的旅行：哪一天、当晚住哪（酒店名和坐标）。 */
    private record Ongoing(Trip trip, Itinerary.Day day, int dayNo, int totalDays, String stayName, double[] stayPoint) {
    }

    private Optional<Ongoing> ongoing(UserCtx ctx, LocalDate today) {
        return tripService.list(ctx).stream()
                .filter(t -> t.getStartDate() != null && t.getEndDate() != null)
                .filter(t -> !t.getStartDate().isAfter(today) && !t.getEndDate().isBefore(today))
                .sorted(Comparator.comparing((Trip t) -> "CONFIRMED".equals(t.getStatus()) ? 0 : 1))
                .map(t -> {
                    Itinerary it = itinerary(ctx, t);
                    Itinerary.Day day = it == null || it.days() == null ? null : it.days().stream()
                            .filter(d -> today.toString().equals(d.date())).findFirst().orElse(null);
                    if (day == null) {
                        return null;
                    }
                    Itinerary.Stay stay = it.stays() == null ? null : it.stays().stream()
                            .filter(s -> s.covers(today.toString()) && s.located()).findFirst().orElse(null);
                    return new Ongoing(t, day, (int) ChronoUnit.DAYS.between(t.getStartDate(), today) + 1,
                            (int) ChronoUnit.DAYS.between(t.getStartDate(), t.getEndDate()) + 1,
                            stay == null ? null : stay.name(), stay == null ? null : new double[]{stay.lng(), stay.lat()});
                })
                .filter(Objects::nonNull).findFirst();
    }

    private Itinerary itinerary(UserCtx ctx, Trip trip) {
        try {
            String json = tripService.get(ctx, trip.getId(), null).version().getItinerary();
            return json == null ? null : objectMapper.readValue(json, Itinerary.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 日程地点找坐标：先按地点名在城市里找，找不到按地址解析；都没有返回空。 */
    private double[] locate(String location, String city) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        try {
            Optional<AmapPlaceService.Place> p = places.find(location, city);
            if (p.isPresent() && p.get().lng() != null) {
                return new double[]{p.get().lng(), p.get().lat()};
            }
            return places.center((StringUtils.hasText(city) ? city : "") + location).orElse(null);
        } catch (RuntimeException e) {
            log.warn("今日安排找 {} 的位置失败：{}", location, e.getMessage());
            return null;
        }
    }

    private Optional<AmapPlaceService.Route> route(double[] from, double[] to) {
        try {
            return places.driving(from, to);
        } catch (RuntimeException e) {
            log.warn("今日安排查路线失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    private static LocalTime parseTime(String hhmm) {
        try {
            return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
