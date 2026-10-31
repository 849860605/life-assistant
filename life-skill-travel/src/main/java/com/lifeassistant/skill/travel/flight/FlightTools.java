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

    public FlightTools(List<FlightPriceSource> sources, CardHub cards) {
        this.sources = sources;
        this.cards = cards;
    }

    /** 某一航班在各平台的价格。 */
    public record PlatformPrice(Integer price, String url) {
    }

    /**
     * 合并后的一个航班：基本信息取第一个查到它的平台，prices 是各平台价格，lowest 是最低价和平台。
     */
    public record Flight(String flightNo, String airline, String depTime, String arrTime, String depAirport,
                         String arrAirport, String note, Map<String, PlatformPrice> prices, Integer lowest,
                         String lowestPlatform) {
    }

    @Tool(description = """
            查单程机票并在飞猪、携程、美团之间比价。返回按航班号合并的航班列表（起降时间、机场、各平台价格和预订链接、最低价），
            按最低价从低到高排；各平台没查到的原因在 platforms 里。往返就去程、回程各调一次。查一次要十几秒，调用前先告诉用户。""")
    public Map<String, Object> compareFlights(
            @ToolParam(description = "出发城市，例如：深圳") String origin,
            @ToolParam(description = "到达城市，例如：北京") String destination,
            @ToolParam(description = "出发日期 yyyy-MM-dd") String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            return Map.of("error", "日期格式不对，要 yyyy-MM-dd");
        }
        List<FlightPriceSource> active = sources.stream().filter(FlightPriceSource::available).toList();
        List<FlightPriceSource.Result> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FlightPriceSource.Result>> futures = active.stream()
                    .map(s -> executor.submit(() -> s.search(origin, destination, day))).toList();
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
        List<Flight> flights = merge(results);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin", origin);
        out.put("destination", destination);
        out.put("date", day.toString());
        out.put("platforms", platforms);
        out.put("flights", flights);
        if (!flights.isEmpty()) {
            Map<String, Object> card = new LinkedHashMap<>(out);
            card.put("queriedAt", CardHub.now());
            if (cards.emit("flights", card, summary(origin, destination, day, flights))) {
                out.put("card", CardHub.SHOWN_HINT);
            }
        }
        return out;
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

    /** 按航班号合并各平台结果，算出最低价，按最低价排序并截取前 MAX_FLIGHTS 个。 */
    static List<Flight> merge(List<FlightPriceSource.Result> results) {
        Map<String, FlightPriceSource.Offer> info = new LinkedHashMap<>();
        Map<String, Map<String, PlatformPrice>> prices = new LinkedHashMap<>();
        for (FlightPriceSource.Result result : results) {
            for (FlightPriceSource.Offer offer : result.offers()) {
                info.putIfAbsent(offer.flightNo(), offer);
                prices.computeIfAbsent(offer.flightNo(), k -> new LinkedHashMap<>())
                        .putIfAbsent(result.platform(), new PlatformPrice(offer.price(), offer.url()));
            }
        }
        List<Flight> flights = new ArrayList<>();
        info.forEach((no, offer) -> {
            Map<String, PlatformPrice> byPlatform = prices.get(no);
            Map.Entry<String, PlatformPrice> cheapest = byPlatform.entrySet().stream()
                    .filter(e -> e.getValue().price() != null)
                    .min(Comparator.comparing(e -> e.getValue().price())).orElse(null);
            flights.add(new Flight(no, offer.airline(), offer.depTime(), offer.arrTime(), offer.depAirport(),
                    offer.arrAirport(), offer.note(), byPlatform, cheapest == null ? null : cheapest.getValue().price(),
                    cheapest == null ? null : cheapest.getKey()));
        });
        flights.sort(Comparator.comparing(Flight::lowest, Comparator.nullsLast(Comparator.naturalOrder())));
        return flights.size() > MAX_FLIGHTS ? flights.subList(0, MAX_FLIGHTS) : flights;
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
