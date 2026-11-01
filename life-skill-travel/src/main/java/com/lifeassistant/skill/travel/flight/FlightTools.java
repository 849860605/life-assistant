package com.lifeassistant.skill.travel.flight;

import com.lifeassistant.core.card.CardHub;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 机票比价：同时向飞猪、携程、美团查同一航线，按航班号合并成一张表。 */
@Component
public class FlightTools {

    private static final long DEADLINE_SECONDS = 60;
    /** 合并后最多返回的航班数，按最低价排序 */
    private static final int MAX_FLIGHTS = 12;

    private final List<FlightPriceSource> sources;
    private final CardHub cards;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    public FlightTools(List<FlightPriceSource> sources, CardHub cards, com.lifeassistant.core.cache.QueryCache queryCache) {
        this.sources = sources;
        this.cards = cards;
        this.queryCache = queryCache;
    }

    /** 某一航班在各平台的价格；tax 是另付的税费（只有途牛单独给），没有为空。 */
    public record PlatformPrice(Integer price, String url, Integer tax) {
    }

    /**
     * 合并后的一个航班：基本信息取第一个查到它的平台，prices 是各平台价格，lowest 是最低价和平台；
     * minutes（全程分钟）、aircraft（机型）、depCode/arrCode（机场三字码）、via（中转城市）取任一平台给了的；
     * transfer 是中转，nextDay 是次日到达。
     */
    public record Flight(String flightNo, String airline, String depTime, String arrTime, String depAirport,
                         String arrAirport, String note, Map<String, PlatformPrice> prices, Integer lowest,
                         String lowestPlatform, Integer minutes, String aircraft, boolean transfer, boolean nextDay,
                         String depCode, String arrCode, String via) {
    }

    /** 某天的最低价（飞猪）：没查到时 price 为空。 */
    public record DayPrice(String date, Integer price, String flightNo, String depTime) {
    }

    /** 页面上的比价最多列这么多班 */
    private static final int PAGE_FLIGHTS = 40;

    @Tool(description = """
            查单程机票并在飞猪、携程、美团之间比价。返回按航班号合并的航班列表（起降时间、机场、各平台价格和预订链接、最低价），
            按最低价从低到高排；各平台没查到的原因在 platforms 里。往返就去程、回程各调一次。查一次要十几秒，调用前先告诉用户。""")
    public Map<String, Object> compareFlights(
            @ToolParam(description = "出发城市，例如：深圳") String origin,
            @ToolParam(description = "到达城市，例如：北京") String destination,
            @ToolParam(description = "出发日期 yyyy-MM-dd") String date) {
        Map<String, Object> out = compare(origin, destination, date, MAX_FLIGHTS);
        @SuppressWarnings("unchecked")
        List<Flight> flights = (List<Flight>) out.get("flights");
        if (flights != null && !flights.isEmpty()) {
            Map<String, Object> card = new LinkedHashMap<>(out);
            card.put("queriedAt", CardHub.now());
            if (cards.emit("flights", card, summary(origin, destination, LocalDate.parse(date.trim()), flights))) {
                out.put("card", CardHub.SHOWN_HINT);
            }
        }
        return out;
    }

    /**
     * 机票比价页用：和对话里一样查各平台，列得多一些（不发卡片），带查询时间；cabin 是 ECONOMY/BUSINESS/FIRST。
     * 结果缓存 30 分钟，refresh 为 true 时重新查。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String origin, String destination, String date, String cabin, boolean refresh) {
        return queryCache.get("flights", origin + "|" + destination + "|" + date + "|" + cabin, java.time.Duration.ofMinutes(30),
                refresh, Map.class, () -> {
                    Map<String, Object> out = compare(origin, destination, date, PAGE_FLIGHTS, cabin);
                    out.put("cabin", cabin == null ? "ECONOMY" : cabin.toUpperCase());
                    out.put("queriedAt", CardHub.now());
                    return out;
                }, m -> !m.containsKey("error") && m.get("flights") instanceof List<?> l && !l.isEmpty());
    }

    /**
     * 前后几天每天的最低价（只查飞猪，每天按价格从低到高查一次取第一班），给价格日历用。缓存 2 小时。
     */
    public List<DayPrice> calendar(String origin, String destination, LocalDate center, int around, boolean refresh) {
        return queryCache.get("flight-calendar", origin + "|" + destination + "|" + center + "|" + around,
                java.time.Duration.ofHours(2), refresh, queryCache.listOf(DayPrice.class),
                () -> loadCalendar(origin, destination, center, around),
                list -> list.stream().anyMatch(d -> d.price() != null));
    }

    private List<DayPrice> loadCalendar(String origin, String destination, LocalDate center, int around) {
        FliggyFlightSource fliggy = sources.stream().filter(FliggyFlightSource.class::isInstance)
                .map(FliggyFlightSource.class::cast).filter(FliggyFlightSource::available).findFirst().orElse(null);
        if (fliggy == null) {
            return List.of();
        }
        int n = Math.max(0, Math.min(around, 3));
        List<LocalDate> days = new ArrayList<>();
        for (int i = -n; i <= n; i++) {
            LocalDate d = center.plusDays(i);
            if (!d.isBefore(LocalDate.now())) {
                days.add(d);
            }
        }
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(2);
        List<DayPrice> out = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DayPrice>> futures = days.stream().map(d -> executor.submit(() -> {
                gate.acquire();
                try {
                    FlightPriceSource.Offer o = fliggy.lowest(origin, destination, d);
                    return new DayPrice(d.toString(), o == null ? null : o.price(), o == null ? null : o.flightNo(),
                            o == null ? null : o.depTime());
                } finally {
                    gate.release();
                }
            })).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(new DayPrice(days.get(i).toString(), null, null, null));
                } catch (Exception e) {
                    out.add(new DayPrice(days.get(i).toString(), null, null, null));
                }
            }
        }
        return out;
    }

    private Map<String, Object> compare(String origin, String destination, String date, int limit) {
        return compare(origin, destination, date, limit, null);
    }

    private Map<String, Object> compare(String origin, String destination, String date, int limit, String cabin) {
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            return new LinkedHashMap<>(Map.of("error", "日期格式不对，要 yyyy-MM-dd"));
        }
        if (day.isBefore(LocalDate.now())) {
            return new LinkedHashMap<>(Map.of("error", day + " 已经过去了，换一个今天以后的日期再查"));
        }
        List<FlightPriceSource> active = sources.stream().filter(FlightPriceSource::available).toList();
        List<FlightPriceSource.Result> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FlightPriceSource.Result>> futures = active.stream()
                    .map(s -> executor.submit(() -> s.search(origin, destination, day, cabin))).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(futures.get(i), active.get(i).platform(), deadline));
            }
            executor.shutdownNow();
        }
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (FlightPriceSource.Result r : results) {
            platforms.put(r.platform(), r.status().equals("OK") ? "OK，" + r.offers().size() + " 个航班"
                    : r.status() + (r.note() == null ? "" : "：" + r.note()));
        }
        List<Flight> flights = merge(results, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin", origin);
        out.put("destination", destination);
        out.put("date", day.toString());
        out.put("platforms", platforms);
        out.put("flights", flights);
        if (flights.isEmpty()) {
            out.put("notice", emptyNotice(day, results));
        }
        return out;
    }

    /** 离出发超过这么多天还一班都查不到，多半是航司还没放出这天的航班 */
    private static final int FAR_DAYS = 300;

    /**
     * 一班都没查到时给用户看的一句话：日期太远多半是还没开售；额度用完、平台出错单独说明；其余是这条航线这天没有直飞或平台都没有数据。
     */
    static String emptyNotice(LocalDate day, List<FlightPriceSource.Result> results) {
        long ahead = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), day);
        if (ahead > FAR_DAYS) {
            return day.getMonthValue() + "月" + day.getDayOfMonth() + "日离现在还有 " + ahead
                    + " 天，各平台都还查不到这天的航班，多半是航司还没开售。建议出发前两三个月再来比价。";
        }
        boolean quota = results.stream().anyMatch(r -> r.note() != null && r.note().contains("额度"));
        boolean failed = results.stream().allMatch(r -> "FAILED".equals(r.status()));
        if (failed) {
            return quota ? "这会儿各平台的查询额度用完或暂时出错了，过一会儿或明天再试。" : "各平台这会儿都查询出错了，稍后再试一次。";
        }
        return "各平台都没查到这天的航班，可能这条航线没有直飞，或者这天已经售完。可以换个日期，或者查附近城市的机场。";
    }

    /** 卡片的文字版：序号、航班号、起降时间、最低价和平台。 */
    private static String summary(String origin, String destination, LocalDate day, List<Flight> flights) {
        StringBuilder out = new StringBuilder("机票 " + origin + "→" + destination + " " + day + "：");
        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            out.append(i == 0 ? "" : "；").append(i + 1).append(". ").append(f.flightNo()).append(" ")
                    .append(f.depTime()).append("-").append(f.arrTime())
                    .append(f.lowest() == null ? "" : " 最低" + f.lowest() + "元(" + f.lowestPlatform() + ")");
        }
        return out.toString();
    }

    static List<Flight> merge(List<FlightPriceSource.Result> results) {
        return merge(results, MAX_FLIGHTS);
    }

    /** 按航班号合并各平台结果，算出最低价，按最低价排序并截取前 limit 个。 */
    static List<Flight> merge(List<FlightPriceSource.Result> results, int limit) {
        Map<String, FlightPriceSource.Offer> info = new LinkedHashMap<>();
        Map<String, Map<String, PlatformPrice>> prices = new LinkedHashMap<>();
        Map<String, Integer> minutes = new LinkedHashMap<>();
        Map<String, String> aircraft = new LinkedHashMap<>();
        Map<String, FlightPriceSource.Offer> coded = new LinkedHashMap<>();
        for (FlightPriceSource.Result result : results) {
            for (FlightPriceSource.Offer offer : result.offers()) {
                info.putIfAbsent(offer.flightNo(), offer);
                if (offer.minutes() != null) {
                    minutes.putIfAbsent(offer.flightNo(), offer.minutes());
                }
                if (offer.aircraft() != null && !offer.aircraft().isBlank()) {
                    aircraft.putIfAbsent(offer.flightNo(), offer.aircraft());
                }
                if (offer.depCode() != null) {
                    coded.putIfAbsent(offer.flightNo(), offer);
                }
                prices.computeIfAbsent(offer.flightNo(), k -> new LinkedHashMap<>())
                        .putIfAbsent(result.platform(), new PlatformPrice(offer.price(), offer.url(), offer.tax()));
            }
        }
        List<Flight> flights = new ArrayList<>();
        info.forEach((no, offer) -> {
            Map<String, PlatformPrice> byPlatform = prices.get(no);
            Map.Entry<String, PlatformPrice> cheapest = byPlatform.entrySet().stream()
                    .filter(e -> e.getValue().price() != null)
                    .min(Comparator.comparing(e -> e.getValue().price())).orElse(null);
            boolean nextDay = offer.depTime() != null && offer.arrTime() != null
                    && pad(offer.arrTime()).compareTo(pad(offer.depTime())) < 0;
            flights.add(new Flight(no, offer.airline(), offer.depTime(), offer.arrTime(), offer.depAirport(),
                    offer.arrAirport(), offer.note(), byPlatform, cheapest == null ? null : cheapest.getValue().price(),
                    cheapest == null ? null : cheapest.getKey(), minutes.get(no), aircraft.get(no),
                    no.contains("/") || "中转".equals(offer.note()), nextDay,
                    coded.containsKey(no) ? coded.get(no).depCode() : null,
                    coded.containsKey(no) ? coded.get(no).arrCode() : null,
                    coded.containsKey(no) ? coded.get(no).via() : null));
        });
        flights.sort(Comparator.comparing(Flight::lowest, Comparator.nullsLast(Comparator.naturalOrder())));
        return flights.size() > limit ? new ArrayList<>(flights.subList(0, limit)) : flights;
    }

    /** "7:15" 补成 "07:15"，方便比较先后。 */
    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    /** 在总截止时间前取结果，超时或出错记为失败。 */
    private static FlightPriceSource.Result await(Future<FlightPriceSource.Result> future, String platform, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FlightPriceSource.Result.failed(platform, "查询被中断");
        } catch (Exception e) {
            future.cancel(true);
            return FlightPriceSource.Result.failed(platform, "查询超时");
        }
    }
}
