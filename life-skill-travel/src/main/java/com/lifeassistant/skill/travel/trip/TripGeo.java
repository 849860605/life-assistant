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
 * 再算每天相邻两项之间的路程（1 公里内按步行估，远的驾车、公交都查，默认选地铁公交，按用户偏好再换见 Itinerary.resolveLegs）。高德没配或出错时原样返回，不影响行程本身。
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
        items = dropOutliers(day.items(), items, city);
        return new Itinerary.Day(day.date(), day.theme(), items, legs(items, cityCode(city, items)));
    }

    /** 新找到的地点离当天其他地点的中位位置超过这么远，多半是同名的别处（比如别的区县的"龙井村"） */
    private static final double OUTLIER_KM = 50;

    /**
     * 检查这次新补上坐标的项：离当天其他地点太远的，用地址再找一次；还是太远就不要这个坐标，免得地图和路程算错。
     */
    private List<Itinerary.Item> dropOutliers(List<Itinerary.Item> before, List<Itinerary.Item> after, String city) {
        List<Itinerary.Item> out = new ArrayList<>(after);
        for (int i = 0; i < out.size(); i++) {
            Itinerary.Item item = out.get(i);
            if (!item.located() || before.get(i).located()) {
                continue;
            }
            List<Itinerary.Item> others = new ArrayList<>(out);
            others.remove(i);
            double[] center = median(others);
            if (center == null || distanceKm(center[1], center[0], item.lat(), item.lng()) <= OUTLIER_KM) {
                continue;
            }
            Itinerary.Item original = before.get(i);
            AmapPlaceService.Place retry = null;
            if (original.address() != null && !original.address().isBlank()) {
                try {
                    retry = places.search(original.address().replaceAll("[(（].*?[)）]", ""), city, null, 1).stream()
                            .filter(p -> p.lng() != null
                                    && distanceKm(center[1], center[0], p.lat(), p.lng()) <= OUTLIER_KM)
                            .findFirst().orElse(null);
                } catch (RuntimeException e) {
                    log.warn("按地址重找 {} 失败：{}", original.name(), e.getMessage());
                }
            }
            AmapPlaceService.Place p = retry;
            out.set(i, p == null ? original : new Itinerary.Item(original.id(), original.start(), original.end(),
                    original.type(), original.name(), original.address(), original.note(), original.cost(),
                    original.url(), original.locked(), p.id(), p.lng(), p.lat(), p.photo(), p.rating()));
        }
        return out;
    }

    /** 有坐标的项的中位位置（经度、纬度分别取中位数）；少于 2 个返回空。 */
    private static double[] median(List<Itinerary.Item> items) {
        List<Double> lngs = items.stream().filter(Itinerary.Item::located).map(Itinerary.Item::lng).sorted().toList();
        List<Double> lats = items.stream().filter(Itinerary.Item::located).map(Itinerary.Item::lat).sorted().toList();
        if (lngs.size() < 2) {
            return null;
        }
        return new double[]{lngs.get(lngs.size() / 2), lats.get(lats.size() / 2)};
    }

    /** 有没有该有坐标、却还没找到坐标的地点（早先目的地名不规范时会找不到）。 */
    public static boolean missingPlaces(Itinerary itinerary) {
        return itinerary.days().stream().flatMap(d -> d.items().stream())
                .anyMatch(i -> PLACE_TYPES.contains(i.type()) && !i.located() && !"HOTEL".equals(i.type()));
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
    private List<Itinerary.Leg> legs(List<Itinerary.Item> items, String cityCode) {
        List<Itinerary.Leg> legs = new ArrayList<>();
        Itinerary.Item prev = null;
        for (Itinerary.Item item : items) {
            if (!item.located()) {
                continue;
            }
            if (prev != null) {
                Itinerary.Leg leg = leg(prev, item, cityCode);
                if (leg != null) {
                    legs.add(leg);
                }
            }
            prev = item;
        }
        return legs;
    }

    /**
     * 当天所在城市的 citycode，查公交用：先按目的地名（去掉括号里的补充）查，查不到用当天第一个有坐标的地点反查；
     * 都查不到为空，公交就不查了。
     */
    private String cityCode(String city, List<Itinerary.Item> items) {
        try {
            String name = city == null ? null : city.replaceAll("[(（].*?[)）]", "").split("[·・,，、\\s]")[0];
            String code = places.cityCode(name).orElse(null);
            if (code != null) {
                return code;
            }
            Itinerary.Item first = items.stream().filter(Itinerary.Item::located).findFirst().orElse(null);
            return first == null ? null : places.cityCodeAt(first.lng(), first.lat()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("城市 {} 的 citycode 查询失败：{}", city, e.getMessage());
            return null;
        }
    }

    private Itinerary.Leg leg(Itinerary.Item a, Itinerary.Item b, String cityCode) {
        double km = distanceKm(a.lat(), a.lng(), b.lat(), b.lng());
        if (km <= WALK_KM) {
            // 步行按每小时 4.5 公里，绕路系数 1.3
            return new Itinerary.Leg(a.id(), b.id(), "WALK", Math.max(1, (int) Math.round(km * 1.3 / 4.5 * 60)),
                    Math.round(km * 1.3 * 10) / 10.0);
        }
        double[] from = {a.lng(), a.lat()};
        double[] to = {b.lng(), b.lat()};
        AmapPlaceService.Route drive = null;
        try {
            drive = places.driving(from, to).orElse(null);
        } catch (RuntimeException e) {
            log.warn("驾车路线 {} → {} 查询失败：{}", a.name(), b.name(), e.getMessage());
        }
        // 查过没有公交方案（或者不知道在哪个城市、没法查）记成 minutes=0，免得每次打开行程都重查；
        // 查询出错（比如超额度）记空，下次再试
        Itinerary.Transit transit = new Itinerary.Transit(0, null, null, null);
        if (cityCode != null) {
            try {
                transit = places.transit(from, to, cityCode)
                        .map(t -> new Itinerary.Transit(t.minutes(), t.fee(), t.walkKm(), t.lines()))
                        .orElse(new Itinerary.Transit(0, null, null, null));
            } catch (RuntimeException e) {
                log.warn("公交路线 {} → {} 查询失败：{}", a.name(), b.name(), e.getMessage());
                transit = null;
            }
        }
        if (drive == null) {
            return transit != null && transit.minutes() > 0
                    ? new Itinerary.Leg(a.id(), b.id(), Itinerary.TRANSIT, transit.minutes(),
                    Math.round(km * 1.4 * 10) / 10.0, null, null, null, transit)
                    : null;
        }
        int minutes = Math.max(1, drive.minutes());
        return new Itinerary.Leg(a.id(), b.id(), Itinerary.DRIVE, minutes, drive.km(), drive.taxi(), minutes, drive.km(),
                transit).choose(Itinerary.TRANSIT);
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
