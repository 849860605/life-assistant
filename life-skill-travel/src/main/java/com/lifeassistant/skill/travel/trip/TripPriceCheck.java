package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.skill.travel.flight.FlightBook;
import com.lifeassistant.skill.travel.flight.FlightTools;
import com.lifeassistant.skill.travel.hotel.HotelPriceSource;
import com.lifeassistant.skill.travel.hotel.HotelPriceTools;
import com.lifeassistant.skill.travel.hotel.HotelQuoteBook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

/**
 * 行程里酒店、机票的后台补比价。规划时只查了一个平台（酒店 RollingGo、机票飞猪），行程先出来；
 * 保存后在后台只给选中的那几家酒店、那几班航班去其他平台查价，查完原地更新当前版本（不算新版本）。
 * 途牛额度太紧，后台不查，只在用户主动比价时用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripPriceCheck {

    /** 酒店后台补价不查的平台 */
    static final Set<String> HOTEL_SKIP = Set.of("途牛");
    /** 机票后台补价不查的平台：飞猪已经查过 */
    static final Set<String> FLIGHT_SKIP = Set.of("飞猪", "途牛");

    private final HotelQuoteBook quoteBook;
    private final FlightBook flightBook;
    private final HotelPriceTools hotelPriceTools;
    private final FlightTools flightTools;
    private final TripEditor tripEditor;

    /**
     * 要补比价的一项：key 是页面上用来对上的键（酒店"名称|入住日期"，航班"航班号|日期"）；
     * 酒店用 name、city、checkIn、checkOut，航班用 name（航班号）、origin、destination、checkIn（起飞日期）。
     */
    public record Target(String key, boolean hotel, String name, String city, String checkIn, String checkOut,
                         String origin, String destination) {
    }

    /** 保存前的准备结果：航班价格和链接已经从航班簿填好的行程，和要后台补比价的项。 */
    public record Prepared(Itinerary itinerary, List<Target> targets) {
    }

    /** 酒店的键 */
    public static String hotelKey(String name, String checkIn) {
        return name + "|" + checkIn;
    }

    /** 航班的键 */
    public static String flightKey(String flightNo, String date) {
        return flightNo + "|" + date;
    }

    /**
     * 保存行程前：交通项里写了航班号的，按这个会话查过的航班填上价格和预订链接；
     * 只查过一个平台的住宿和航班列为要后台补比价的项。
     */
    public Prepared prepare(Itinerary itinerary, String conversationId) {
        List<Target> targets = new ArrayList<>();
        List<Itinerary.Day> days = new ArrayList<>();
        for (Itinerary.Day day : itinerary.days()) {
            List<Itinerary.Item> items = new ArrayList<>();
            for (Itinerary.Item item : day.items()) {
                FlightBook.Entry entry = flightOf(item, day.date(), conversationId);
                if (entry == null) {
                    items.add(item);
                    continue;
                }
                FlightTools.Flight f = entry.flight();
                FlightTools.PlatformPrice best = f.lowestPlatform() == null ? null : f.prices().get(f.lowestPlatform());
                String note = item.note() != null && item.note().contains("¥") ? item.note()
                        : (f.lowest() == null ? "" : f.lowestPlatform() + " ¥" + f.lowest() + "，")
                        + (item.note() == null ? "价格以平台为准；订好后回来标记已预约" : item.note());
                items.add(new Itinerary.Item(item.id(), item.start(), item.end(), item.type(), item.name(), item.address(),
                        note, f.lowest() != null ? f.lowest() : item.cost(), best != null && best.url() != null
                        ? best.url() : item.url(), item.locked(), item.poiId(), item.lng(), item.lat(), item.photo(),
                        item.rating()));
                if (entry.quick()) {
                    targets.add(new Target(flightKey(f.flightNo(), day.date()), false, f.flightNo(), null, day.date(),
                            null, entry.origin(), entry.destination()));
                }
            }
            days.add(new Itinerary.Day(day.date(), day.theme(), items, day.legs()));
        }
        for (Itinerary.Stay st : itinerary.stays() == null ? List.<Itinerary.Stay>of() : itinerary.stays()) {
            if (st.price() == null || st.quotes() != null && st.quotes().size() > 1) {
                continue;
            }
            HotelQuoteBook.Found found = quoteBook.find(conversationId, st.name()).orElse(null);
            if (found != null && found.comparison().city() != null) {
                targets.add(new Target(hotelKey(st.name(), st.checkIn()), true, st.name(), found.comparison().city(),
                        st.checkIn(), st.checkOut(), null, null));
            }
        }
        return new Prepared(itinerary.withDays(days), targets);
    }

    /** 这一项是航班（交通项、名称里有航班号）且这个会话查过它时，返回查到的记录。 */
    private FlightBook.Entry flightOf(Itinerary.Item item, String date, String conversationId) {
        if (!"TRANSPORT".equals(item.type()) || item.name() == null) {
            return null;
        }
        Matcher m = TripEditor.FLIGHT_IN_NAME.matcher(item.name());
        return m.find() ? flightBook.find(conversationId, m.group(), date).orElse(null) : null;
    }

    /** 没有要补的项返回 null；有的话返回给卡片和页面看的"比价中"记录（键 → 开始时间）。 */
    public static Map<String, String> checking(List<Target> targets) {
        if (targets.isEmpty()) {
            return null;
        }
        String now = CardHub.now();
        Map<String, String> out = new LinkedHashMap<>();
        targets.forEach(t -> out.put(t.key(), now));
        return out;
    }

    /** 后台开始补比价：先在当前版本记上"比价中"，各项并行查完再一起写回价格、去掉记录。 */
    public void start(UserCtx ctx, long tripId, List<Target> targets) {
        if (targets.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("trip-price-" + tripId).start(() -> run(ctx, tripId, targets));
    }

    private void run(UserCtx ctx, long tripId, List<Target> targets) {
        Map<String, String> marks = checking(targets);
        try {
            tripEditor.updateInPlace(ctx, tripId, it -> {
                Map<String, String> all = new HashMap<>(it.checking() == null ? Map.of() : it.checking());
                all.putAll(marks);
                return it.withChecking(all);
            });
        } catch (RuntimeException e) {
            log.warn("行程 {} 记比价中失败：{}", tripId, e.getMessage());
        }
        Map<String, List<HotelPriceSource.Quote>> hotels = new HashMap<>();
        Map<String, Map<String, FlightTools.PlatformPrice>> flights = new HashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Target t : targets) {
                futures.add(executor.submit(() -> {
                    try {
                        if (t.hotel()) {
                            List<HotelPriceSource.Quote> q = hotelPriceTools.refine(t.name(), t.city(),
                                    LocalDate.parse(t.checkIn()), LocalDate.parse(t.checkOut()), HOTEL_SKIP);
                            synchronized (hotels) {
                                hotels.put(t.key(), q);
                            }
                        } else {
                            Map<String, FlightTools.PlatformPrice> p = flightTools.refine(t.origin(), t.destination(),
                                    LocalDate.parse(t.checkIn()), t.name(), FLIGHT_SKIP);
                            synchronized (flights) {
                                flights.put(t.key(), p);
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("行程 {} 补比价 {} 失败：{}", tripId, t.key(), e.getMessage());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // 单项失败已经记了日志，其余照常写回
                }
            }
        }
        try {
            tripEditor.updateInPlace(ctx, tripId, it -> apply(it, hotels, flights, marks.keySet()));
        } catch (RuntimeException e) {
            log.warn("行程 {} 写回比价失败：{}", tripId, e.getMessage());
        }
    }

    /**
     * 把查到的价格合进行程：酒店按"名称|入住日期"对上住宿，各平台报价合并后取最低；航班按"航班号|日期"对上交通项，
     * 价格写成最低价、说明里列各平台价格。最后去掉这批的"比价中"记录。
     */
    static Itinerary apply(Itinerary it, Map<String, List<HotelPriceSource.Quote>> hotels,
                           Map<String, Map<String, FlightTools.PlatformPrice>> flights, Set<String> done) {
        String now = CardHub.now();
        List<Itinerary.Stay> stays = it.stays() == null ? null : it.stays().stream().map(st -> {
            List<HotelPriceSource.Quote> found = hotels.get(hotelKey(st.name(), st.checkIn()));
            if (found == null) {
                return st;
            }
            Map<String, Itinerary.StayQuote> merged = new LinkedHashMap<>();
            (st.quotes() == null ? List.<Itinerary.StayQuote>of() : st.quotes()).forEach(q -> merged.put(q.platform(), q));
            found.stream().filter(q -> q.price() != null).forEach(q -> merged.put(q.platform(),
                    new Itinerary.StayQuote(q.platform(), q.price(), q.url(), q.basis(), q.matchedName())));
            Itinerary.StayQuote best = merged.values().stream().filter(q -> q.price() != null)
                    .min(Comparator.comparing(Itinerary.StayQuote::price)).orElse(null);
            return best == null ? st : new Itinerary.Stay(st.id(), st.name(), st.checkIn(), st.checkOut(), st.address(),
                    best.price(), best.platform(), best.url(), now, List.copyOf(merged.values()), st.options(),
                    st.poiId(), st.lng(), st.lat(), st.photo(), st.rating());
        }).toList();
        List<Itinerary.Day> days = it.days().stream().map(day -> new Itinerary.Day(day.date(), day.theme(),
                day.items().stream().map(item -> withFlightPrices(item, day.date(), flights, now)).toList(),
                day.legs())).toList();
        Map<String, String> left = new HashMap<>(it.checking() == null ? Map.of() : it.checking());
        done.forEach(left::remove);
        return it.withDays(days).withStays(stays).withChecking(left);
    }

    /** 航班项写上各平台里的最低价、链接，说明里列出各平台价格和查询时间。 */
    private static Itinerary.Item withFlightPrices(Itinerary.Item item, String date,
                                                   Map<String, Map<String, FlightTools.PlatformPrice>> flights, String now) {
        if (!"TRANSPORT".equals(item.type()) || item.name() == null) {
            return item;
        }
        Matcher m = TripEditor.FLIGHT_IN_NAME.matcher(item.name());
        Map<String, FlightTools.PlatformPrice> found = m.find() ? flights.get(flightKey(m.group(), date)) : null;
        if (found == null || found.isEmpty()) {
            return item;
        }
        Map<String, Integer> prices = new LinkedHashMap<>();
        // 原来那个平台（飞猪）的价格写在说明开头"飞猪 ¥xx，"
        Matcher old = java.util.regex.Pattern.compile("^(\\S+) ¥(\\d+)，").matcher(item.note() == null ? "" : item.note());
        String tail = item.note() == null ? "" : item.note();
        if (old.find()) {
            prices.put(old.group(1), Integer.parseInt(old.group(2)));
            tail = tail.substring(old.end());
        }
        found.forEach((p, v) -> prices.put(p, v.price()));
        Map.Entry<String, Integer> best = prices.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow();
        String url = found.containsKey(best.getKey()) && found.get(best.getKey()).url() != null
                ? found.get(best.getKey()).url() : item.url();
        String list = String.join("、", prices.entrySet().stream().map(e -> e.getKey() + " ¥" + e.getValue()).toList());
        String note = best.getKey() + " ¥" + best.getValue() + "，" + "比价：" + list + "；" + tail;
        return new Itinerary.Item(item.id(), item.start(), item.end(), item.type(), item.name(), item.address(), note,
                best.getValue(), url, item.locked(), item.poiId(), item.lng(), item.lat(), item.photo(), item.rating());
    }
}
