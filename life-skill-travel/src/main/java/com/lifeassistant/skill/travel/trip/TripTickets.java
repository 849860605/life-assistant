package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.ticket.TicketSearch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 行程某天的门票安排：当天每个景点在飞猪上对上景点（是否免费、等级）和门票商品（成人价、儿童价、预订链接）。
 * 对不上或没有门票商品的标成待查，不估价。只给预订链接，不下单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripTickets {

    private static final long DEADLINE_SECONDS = 30;

    private final TripService tripService;
    private final TripEditor editor;
    private final TicketSearch tickets;

    /**
     * 当天一个景点的门票：spot 是飞猪上对上的景点（对不上为空），free 是飞猪标的免费景点，
     * product 是挑的门票商品（单纯门票里能看出成人价的；没有为空，页面上显示待查），error 是查询失败的原因。
     */
    public record SpotTicket(String itemId, String name, String start, TicketSearch.Spot spot, Boolean free,
                             TicketSearch.Product product, String error) {
    }

    public record DayTickets(int day, String date, List<SpotTicket> spots, String queriedAt) {
    }

    public DayTickets day(UserCtx ctx, long tripId, int dayIndex, boolean refresh) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = editor.itinerary(detail);
        if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
            throw BizException.notFound("这一天");
        }
        Itinerary.Day day = itinerary.days().get(dayIndex);
        String city = detail.trip().getDestination() == null ? "" : detail.trip().getDestination().split("[·・\\s,，]")[0];
        List<Itinerary.Item> spots = day.items().stream().filter(i -> "SPOT".equals(i.type())).limit(8).toList();
        List<SpotTicket> out = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SpotTicket>> futures = spots.stream().map(i -> executor.submit(() -> one(city, i, refresh))).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            for (int k = 0; k < futures.size(); k++) {
                Itinerary.Item i = spots.get(k);
                try {
                    out.add(futures.get(k).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(new SpotTicket(i.id(), i.name(), i.start(), null, null, null, "查询被中断"));
                } catch (Exception e) {
                    out.add(new SpotTicket(i.id(), i.name(), i.start(), null, null, null, "飞猪暂时查不到"));
                }
            }
        }
        return new DayTickets(dayIndex + 1, day.date(), out, CardHub.now());
    }

    private SpotTicket one(String city, Itinerary.Item item, boolean refresh) {
        String name = TicketSearch.shortName(item.name());
        TicketSearch.Spot spot = match(name, tickets.spots(city, name, null, refresh));
        TicketSearch.Product product = null;
        if (spot == null || !Boolean.TRUE.equals(spot.free())) {
            // 门票商品标题常用简称（"秦始皇帝陵博物院（兵马俑）"的门票写"兵马俑"），优先用括号里的简称查
            String query = spot == null ? name : alias(spot.name()) != null ? alias(spot.name()) : TicketSearch.shortName(spot.name());
            product = tickets.tickets(city, query, refresh).stream()
                    .filter(p -> !p.bundle() && p.adult() != null).findFirst().orElse(null);
        }
        return new SpotTicket(item.id(), item.name(), item.start(), spot, spot == null ? null : spot.free(), product, null);
    }

    /** 在飞猪的结果里找这个景点：名字互相包含或括号里的简称对上；有 A 级的、不是馆内分展（名字带 -）的、名字短的优先。 */
    static TicketSearch.Spot match(String name, List<TicketSearch.Spot> found) {
        return found.stream().filter(s -> {
                    String core = TicketSearch.shortName(s.name());
                    String alias = alias(s.name());
                    return AmapPlaceService.similarName(name, s.name()) || name.contains(core) || core.contains(name)
                            || alias != null && name.contains(alias);
                })
                .min(java.util.Comparator.comparing((TicketSearch.Spot s) -> s.level() == null)
                        .thenComparing(s -> s.name().contains("-"))
                        .thenComparing(s -> s.name().length()))
                .orElse(null);
    }

    /** "秦始皇帝陵博物院（兵马俑）" → "兵马俑"；没有括号返回空。 */
    static String alias(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[（(]([^）)]{2,10})[）)]").matcher(name);
        return m.find() ? m.group(1) : null;
    }
}
