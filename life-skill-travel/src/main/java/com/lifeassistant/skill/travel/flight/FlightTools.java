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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final FlightBook flightBook;

    public FlightTools(List<FlightPriceSource> sources, CardHub cards, com.lifeassistant.core.cache.QueryCache queryCache,
                       FlightBook flightBook) {
        this.sources = sources;
        this.cards = cards;
        this.queryCache = queryCache;
        this.flightBook = flightBook;
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
        CardHub.Tracker progress = cards.track("机票比价 " + origin + " → " + destination);
        Map<String, Object> out = compare(origin, destination, date, MAX_FLIGHTS, null, progress);
        progress.finish(out.containsKey("error") ? "没查成：" + out.get("error")
                : "比价完成，找到 " + ((List<?>) out.get("flights")).size() + " 个航班");
        @SuppressWarnings("unchecked")
        List<Flight> flights = (List<Flight>) out.get("flights");
        if (flights != null && !flights.isEmpty()) {
            record(origin, destination, String.valueOf(out.get("date")), flights);
            Map<String, Object> card = new LinkedHashMap<>(out);
            card.put("queriedAt", CardHub.now());
            if (cards.emit("flights", card, summary(origin, destination, LocalDate.parse(date.trim()), flights))) {
                out.put("card", CardHub.SHOWN_HINT);
            }
        }
        return out;
    }

    /** 快速查价最多给模型这么多班 */
    private static final int QUICK_FLIGHTS = 6;

    /**
     * 规划行程时用的快速查价：只查飞猪（一两秒），不发卡片；挑中的那班写进行程后，后台再去携程、美团补比价。
     */
    @Tool(description = """
            规划完整行程时查大交通用这个：只查飞猪，一两秒出结果，不出比价卡片。按偏好返回几班（航班号、起降时间、机场、价格），
            从里面挑一班写进行程（TRANSPORT 项的 name 里带航班号，如"南航 CZ3401 双流T2 → 萧山T3"），价格和链接后端会自动带上，
            其他平台的价格行程生成后后台补。往返就去程、回程各调一次，可以和酒店、天气一起并行调用。
            用户单独问机票价格、或者说要全部比好价时用 compareFlights。""")
    public Map<String, Object> quickFlights(
            @ToolParam(description = "出发城市，例如：成都") String origin,
            @ToolParam(description = "到达城市，例如：杭州") String destination,
            @ToolParam(description = "出发日期 yyyy-MM-dd") String date,
            @ToolParam(description = "偏好：直飞 / 早班 / 下午 / 晚班 / 便宜，没有就不填", required = false) String preference) {
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            return Map.of("error", "日期格式不对，要 yyyy-MM-dd");
        }
        if (day.isBefore(LocalDate.now())) {
            return Map.of("error", day + " 已经过去了");
        }
        FlightPriceSource fliggy = sources.stream().filter(s -> "飞猪".equals(s.platform()) && s.available()).findFirst()
                .orElse(null);
        if (fliggy == null) {
            return Map.of("error", "飞猪没有配置，改用 compareFlights 查");
        }
        FlightPriceSource.Result result;
        try {
            result = fliggy.search(origin, destination, day);
        } catch (RuntimeException e) {
            result = FlightPriceSource.Result.failed(fliggy.platform(), e.getMessage());
        }
        List<Flight> flights = pick(merge(List.of(result), PAGE_FLIGHTS), preference);
        if (flights.isEmpty()) {
            return Map.of("error", "飞猪没查到这天的航班" + (result.note() == null ? "" : "（" + result.note() + "）")
                    + "，改用 compareFlights 查各平台");
        }
        record(origin, destination, day.toString(), flights);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("flights", flights.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flightNo", f.flightNo());
            m.put("airline", f.airline());
            m.put("time", f.depTime() + "-" + f.arrTime() + (f.nextDay() ? "（次日）" : ""));
            m.put("airports", f.depAirport() + " → " + f.arrAirport());
            m.put("price", f.lowest());
            if (f.transfer()) {
                m.put("transfer", f.via() == null ? "中转" : "经" + f.via() + "中转");
            }
            return m;
        }).toList());
        out.put("note", "价格只来自飞猪；写进行程后后台会补携程、美团的价格");
        return out;
    }

    /** 按偏好挑几班：直飞优先时去掉中转；早班、下午、晚班按起飞时间筛；其余按价格。 */
    static List<Flight> pick(List<Flight> flights, String preference) {
        String p = preference == null ? "" : preference;
        List<Flight> list = new ArrayList<>(flights);
        if (p.contains("直飞") && list.stream().anyMatch(f -> !f.transfer())) {
            list.removeIf(Flight::transfer);
        }
        java.util.function.Predicate<Flight> slot = p.contains("早") ? f -> pad(f.depTime()).compareTo("11:00") < 0
                : p.contains("下午") ? f -> pad(f.depTime()).compareTo("12:00") >= 0 && pad(f.depTime()).compareTo("18:00") < 0
                : p.contains("晚") ? f -> pad(f.depTime()).compareTo("18:00") >= 0 : null;
        if (slot != null && list.stream().anyMatch(slot)) {
            list.removeIf(slot.negate());
        }
        list.sort(Comparator.comparing(Flight::lowest, Comparator.nullsLast(Comparator.naturalOrder())));
        return list.size() > QUICK_FLIGHTS ? new ArrayList<>(list.subList(0, QUICK_FLIGHTS)) : list;
    }

    /** 记进当前会话的航班簿，保存行程时按航班号找回价格和链接。 */
    private void record(String origin, String destination, String date, List<Flight> flights) {
        com.lifeassistant.core.audit.CallRecorder.Scope scope = com.lifeassistant.core.audit.CallRecorder.current();
        flightBook.record(scope == null ? null : scope.conversationId(), origin, destination, date, flights, CardHub.now());
    }

    /**
     * 后台补比价：给行程里选中的一班去其他平台（skip 里的平台不查，比如已经查过的飞猪、额度紧的途牛）查同一航线，
     * 按航班号对上后返回各平台价格；一个平台都没对上返回空 map。
     */
    public Map<String, PlatformPrice> refine(String origin, String destination, LocalDate date, String flightNo,
                                             Set<String> skip) {
        List<FlightPriceSource> active = sources.stream()
                .filter(s -> s.available() && !skip.contains(s.platform())).toList();
        Map<String, PlatformPrice> out = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FlightPriceSource.Result>> futures = active.stream()
                    .map(s -> executor.submit(() -> s.search(origin, destination, date))).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                FlightPriceSource.Result r = await(futures.get(i), active.get(i).platform(), deadline);
                r.offers().stream().filter(o -> flightNo.equalsIgnoreCase(o.flightNo()) && o.price() != null)
                        .min(Comparator.comparing(FlightPriceSource.Offer::price))
                        .ifPresent(o -> out.put(r.platform(), new PlatformPrice(o.price(), o.url(), o.tax())));
            }
            executor.shutdownNow();
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
                    Map<String, Object> out = compare(origin, destination, date, PAGE_FLIGHTS, cabin, CardHub.Tracker.none());
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


    private Map<String, Object> compare(String origin, String destination, String date, int limit, String cabin,
                                        CardHub.Tracker progress) {
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
        Set<String> finished = ConcurrentHashMap.newKeySet();
        progress.update("在 " + String.join("、", active.stream().map(FlightPriceSource::platform).toList()) + " 查 " + day + " 的航班");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FlightPriceSource.Result>> futures = active.stream()
                    .map(s -> executor.submit(() -> {
                        try {
                            return s.search(origin, destination, day, cabin);
                        } finally {
                            finished.add(s.platform());
                            List<String> waiting = active.stream().map(FlightPriceSource::platform)
                                    .filter(p -> !finished.contains(p)).toList();
                            progress.update(finished.size() + "/" + active.size() + " 个平台已返回"
                                    + (waiting.isEmpty() ? "" : "，还在等 " + String.join("、", waiting)));
                        }
                    })).toList();
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
