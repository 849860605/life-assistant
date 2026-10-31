package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 给行程补地理信息：每个景点、餐厅、酒店按名称在高德找到对应地点，补上坐标、照片和评分；
 * 再算每天相邻两项之间的路程（1 公里内按步行估，远的查驾车）。高德没配或出错时原样返回，不影响行程本身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripGeo {

    /** 这些类型才去高德找地点；交通、自由活动的名称多半是"高铁返程""自由活动"，找不到对应地点 */
    private static final Set<String> PLACE_TYPES = Set.of("SPOT", "FOOD", "HOTEL");
    /** 直线距离在这以内按步行算 */
    private static final double WALK_KM = 1.2;

    private final AmapPlaceService places;

    /** 补全没坐标的项和住宿并重算全部路程，返回新的行程（geo=true）。 */
    public Itinerary enrich(Itinerary itinerary, String city) {
        if (!places.available() || itinerary.days() == null) {
            return itinerary;
        }
        List<Itinerary.Stay> stays = new ArrayList<>();
        for (Itinerary.Stay stay : itinerary.stays() == null ? List.<Itinerary.Stay>of() : itinerary.stays()) {
            stays.add(locate(stay, city));
        }
        List<Itinerary.Day> days = new ArrayList<>();
        for (Itinerary.Day day : itinerary.days()) {
            days.add(relink(day, city, stays));
        }
        return itinerary.located(days, stays);
    }

    /** 补上当天没坐标的项（酒店类的泛称关联到当晚的住宿），重算路程。换了、删了、调了顺序之后也用它。 */
    public Itinerary.Day relink(Itinerary.Day day, String city, List<Itinerary.Stay> stays) {
        if (!places.available()) {
            return new Itinerary.Day(day.date(), day.theme(), day.items(), null);
        }
        List<Itinerary.Item> items = new ArrayList<>();
        for (Itinerary.Item item : day.items()) {
            items.add(locate(item, city, day.date(), stays));
        }
        return new Itinerary.Day(day.date(), day.theme(), items, legs(items));
    }

    /** 住宿按酒店名在高德找，补坐标、照片、评分、地址。 */
    public Itinerary.Stay locate(Itinerary.Stay stay, String city) {
        if (stay.located()) {
            return stay;
        }
        try {
            return places.find(stay.name(), city).map(p -> new Itinerary.Stay(stay.id(), stay.name(), stay.checkIn(),
                    stay.checkOut(), p.address(), stay.price(), stay.platform(), stay.url(), stay.queriedAt(),
                    stay.quotes(), stay.options(), p.id(), p.lng(), p.lat(), p.photo(), p.rating())).orElse(stay);
        } catch (RuntimeException e) {
            log.warn("酒店 {} 补坐标失败：{}", stay.name(), e.getMessage());
            return stay;
        }
    }

    private Itinerary.Item locate(Itinerary.Item item, String city, String date, List<Itinerary.Stay> stays) {
        if (item.located() || !PLACE_TYPES.contains(item.type())) {
            return item;
        }
        if ("HOTEL".equals(item.type())) {
            Itinerary.Stay stay = stayFor(stays, date, item.start());
            if (stay != null && stay.located()) {
                return new Itinerary.Item(item.id(), item.start(), item.end(), item.type(), item.name(), stay.name(),
                        item.note(), item.cost(), item.url() == null ? stay.url() : item.url(), item.locked(),
                        stay.poiId(), stay.lng(), stay.lat(), stay.photo(), stay.rating());
            }
        }
        try {
            return places.find(item.name(), city).map(p -> new Itinerary.Item(item.id(), item.start(), item.end(),
                    item.type(), item.name(), item.address() == null ? p.address() : item.address(), item.note(),
                    item.cost() == null ? p.cost() : item.cost(), item.url(), item.locked(), p.id(), p.lng(), p.lat(),
                    p.photo(), p.rating())).orElse(item);
        } catch (RuntimeException e) {
            log.warn("行程地点 {} 补坐标失败：{}", item.name(), e.getMessage());
            return item;
        }
    }

    /**
     * 这一天这个时间对应哪段住宿：换酒店那天，下午两点前算前一家（还没退房），之后算新的一家。
     */
    static Itinerary.Stay stayFor(List<Itinerary.Stay> stays, String date, String start) {
        if (stays == null || date == null) {
            return null;
        }
        List<Itinerary.Stay> covering = stays.stream().filter(s -> s.covers(date)).toList();
        if (covering.size() <= 1) {
            return covering.isEmpty() ? null : covering.get(0);
        }
        boolean morning = start != null && start.compareTo("14:00") < 0;
        return covering.stream().filter(s -> morning ? date.equals(s.checkOut()) : date.equals(s.checkIn()))
                .findFirst().orElse(covering.get(0));
    }

    /** 相邻两项（都有坐标）之间的路程；中间隔着没坐标的项就跳过它，接着连下一项。 */
    private List<Itinerary.Leg> legs(List<Itinerary.Item> items) {
        List<Itinerary.Leg> legs = new ArrayList<>();
        Itinerary.Item prev = null;
        for (Itinerary.Item item : items) {
            if (!item.located()) {
                continue;
            }
            if (prev != null) {
                Itinerary.Leg leg = leg(prev, item);
                if (leg != null) {
                    legs.add(leg);
                }
            }
            prev = item;
        }
        return legs;
    }

    private Itinerary.Leg leg(Itinerary.Item a, Itinerary.Item b) {
        double km = distanceKm(a.lat(), a.lng(), b.lat(), b.lng());
        if (km <= WALK_KM) {
            // 步行按每小时 4.5 公里，绕路系数 1.3
            return new Itinerary.Leg(a.id(), b.id(), "WALK", Math.max(1, (int) Math.round(km * 1.3 / 4.5 * 60)),
                    Math.round(km * 1.3 * 10) / 10.0);
        }
        try {
            return places.driving(new double[]{a.lng(), a.lat()}, new double[]{b.lng(), b.lat()})
                    .map(r -> new Itinerary.Leg(a.id(), b.id(), "DRIVE", Math.max(1, r.minutes()), r.km()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("路线 {} → {} 查询失败：{}", a.name(), b.name(), e.getMessage());
            return null;
        }
    }

    /** 球面距离（公里）。 */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h));
    }
}
