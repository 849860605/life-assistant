package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.memory.MemoryCategory;
import com.lifeassistant.core.memory.MemoryItem;
import com.lifeassistant.core.memory.MemoryService;
import com.lifeassistant.core.memory.MemorySource;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.hotel.HotelPriceTools;
import com.lifeassistant.skill.travel.hotel.HotelQuoteBook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 用户在行程页上直接改行程：换一个、删掉、保留、调顺序、加入行程，以及给行程补坐标和路程。
 * 改内容的操作都存成新版本（作者 USER），能在版本历史里回看；补坐标、保留只改当前版本，不算新版本。
 * "去过了""不感兴趣"会记进偏好记忆，以后规划时避开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripEditor {

    /** 记忆 key 的前缀：去过的、不感兴趣的地方 */
    static final String VISITED = "去过:";
    static final String DISLIKED = "不感兴趣:";

    private static final int AROUND_RADIUS = 8000;
    private static final int MAX_ALTERNATIVES = 12;
    private static final int LOCK_STRIPES = 64;
    /** 一天最多这么多项，防止页面上无限加 */
    private static final int MAX_ITEMS_PER_DAY = 40;
    private static final int MAX_NAME = 100;
    private static final int MAX_TEXT = 300;

    private final TripService tripService;
    private final TripGeo geo;
    private final AmapPlaceService places;
    private final MemoryService memory;
    private final HotelPriceTools hotelPriceTools;
    private final ObjectMapper objectMapper;
    /** 按行程 id 分段的锁：同一行程的修改、补坐标、比价写回排队执行，避免互相覆盖 */
    private final ReentrantLock[] stripes = new ReentrantLock[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * 用户选中的地点（来自候选列表或搜索结果）。type 只在加入行程时用。
     * price、platform、url、quotes 只有酒店有：页面查到的各平台最低价、所在平台、预订链接和各平台报价。
     */
    public record PlacePick(String name, String poiId, String address, Double lng, Double lat, String photo,
                            Double rating, Integer cost, String type, String note, Integer price, String platform,
                            String url, List<Itinerary.StayQuote> quotes) {

        public PlacePick(String name, String poiId, String address, Double lng, Double lat, String photo,
                         Double rating, Integer cost, String type, String note) {
            this(name, poiId, address, lng, lat, photo, rating, cost, type, note, null, null, null, null);
        }
    }

    /** 换掉、删掉某项的原因：去过了、不感兴趣；空表示只是想换。 */
    public enum Reason { VISITED, DISLIKE }

    /** 当前版本还没补过坐标就补上，返回补完的行程。 */
    public TripService.TripDetail ensureGeo(UserCtx ctx, long tripId) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return enrich(detail.trip().getId(), detail.version().getVersion(), detail);
    }

    /** 保存后在后台补坐标，用户打开详情时多半已经补好。 */
    public void ensureGeoLater(long tripId, int version) {
        Thread.ofVirtual().start(() -> {
            try {
                enrich(tripId, version, null);
            } catch (RuntimeException e) {
                log.warn("行程 {} v{} 补坐标失败：{}", tripId, version, e.getMessage());
            }
        });
    }

    /**
     * 某项的候选地点：在它附近找同类、评分高的，排除行程里已有的和用户去过、不感兴趣的。
     * keyword 可以是逗号分隔的几个偏好（如"博物馆,夜景"），分别查再合并，最多 4 个。
     */
    public List<AmapPlaceService.Place> alternatives(UserCtx ctx, long tripId, String itemId, String keyword) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = itinerary(detail);
        Itinerary.Item item = find(itinerary, itemId).item();
        String types = switch (item.type()) {
            case "FOOD" -> AmapPlaceService.FOOD_TYPES;
            case "HOTEL" -> AmapPlaceService.HOTEL_TYPES;
            default -> AmapPlaceService.SPOT_TYPES + "|140100";
        };
        List<String> words = keyword == null ? List.of() : Arrays.stream(keyword.split("[,，]")).map(String::trim)
                .filter(w -> !w.isEmpty()).distinct().limit(4).toList();
        if (!words.isEmpty() && !"FOOD".equals(item.type()) && !"HOTEL".equals(item.type())) {
            // 高德同时给关键字和景点分类时会忽略关键字
            types = null;
        }
        String city = detail.trip().getDestination();
        double[] anchor = anchor(itinerary, item, city);
        if (anchor == null && places.region(city).isEmpty()) {
            // 不知道在哪：宁可不给候选，也不要全国搜出一堆北京的地点
            return List.of();
        }
        Map<String, AmapPlaceService.Place> merged = new LinkedHashMap<>();
        for (String word : words.isEmpty() ? java.util.Collections.<String>singletonList(null) : words) {
            List<AmapPlaceService.Place> found = anchor != null
                    ? places.around(anchor[0], anchor[1], word, types, AROUND_RADIUS, 25)
                    : places.search(word == null ? "景点" : word, city, types, 25);
            found.forEach(p -> merged.putIfAbsent(p.id() == null ? p.name() : p.id(), p));
        }
        List<AmapPlaceService.Place> found = new ArrayList<>(merged.values());
        Set<String> avoid = itinerary.days().stream().flatMap(d -> d.items().stream()).map(Itinerary.Item::name)
                .collect(Collectors.toSet());
        avoid.addAll(avoided(ctx));
        return found.stream()
                .filter(p -> avoid.stream().noneMatch(n -> AmapPlaceService.similarName(n, p.name())))
                .sorted(Comparator.comparing(AmapPlaceService.Place::rating, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_ALTERNATIVES)
                .toList();
    }

    /**
     * 找候选时以哪里为中心：这一项有坐标就用它；没有就按名称在目的地城市里找一次；
     * 还找不到用同一天第一个有坐标的地点。都没有返回 null。
     */
    private double[] anchor(Itinerary itinerary, Itinerary.Item item, String city) {
        if (item.located()) {
            return new double[]{item.lng(), item.lat()};
        }
        try {
            if (places.region(city).isPresent()) {
                Optional<AmapPlaceService.Place> found = places.find(item.name(), city);
                if (found.isPresent() && found.get().lng() != null) {
                    return new double[]{found.get().lng(), found.get().lat()};
                }
            }
        } catch (RuntimeException e) {
            log.warn("找 {} 的位置失败：{}", item.name(), e.getMessage());
        }
        return itinerary.days().stream().filter(d -> d.items().contains(item)).findFirst()
                .flatMap(d -> d.items().stream().filter(Itinerary.Item::located).findFirst())
                .map(i -> new double[]{i.lng(), i.lat()}).orElse(null);
    }

    /**
     * 把某项换成选中的地点，时间不变，重算当天路程。换的是酒店、并且能对上一段住宿（或行程还没有住宿）时，
     * 连住宿一起换：价格、预订链接、其他天"回酒店"的路程都跟着新酒店走，不然时间线和住宿会对不上、也没有价格。
     */
    public TripService.TripDetail replace(UserCtx ctx, long tripId, String itemId, PlacePick pick, Reason reason) {
        PlacePick place = clean(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            Found found = find(itinerary, itemId);
            Itinerary.Item old = found.item();
            Itinerary.Item fresh = replaced(old, place);
            remember(ctx, old.name(), reason);
            if ("HOTEL".equals(old.type())) {
                List<Itinerary.Stay> stays = itinerary.stays() == null ? List.of() : itinerary.stays();
                Itinerary.Stay stay = stays.stream().filter(s -> linked(old, s)).findFirst()
                        .orElseGet(() -> TripGeo.stayFor(stays, itinerary.days().get(found.day()).date(), old.start()));
                if (stay != null || stays.isEmpty()) {
                    return replaceStay(ctx, detail, itinerary, found, fresh, stay, place);
                }
            }
            return save(ctx, detail, unbook(itinerary, old.id()), found.day(), items(itinerary, found.day(), old.id(), fresh),
                    "把「" + old.name() + "」换成「" + place.name() + "」");
        });
    }

    /** 换上的新项：时间、类型沿用原来的，其余用选中的地点（酒店带上预订链接）。 */
    private static Itinerary.Item replaced(Itinerary.Item old, PlacePick place) {
        return new Itinerary.Item(old.id(), old.start(), old.end(), old.type(), place.name(), place.address(),
                place.note(), place.cost(), place.url(), null, place.poiId(), place.lng(), place.lat(), place.photo(),
                place.rating());
    }

    /**
     * 时间线上换酒店：先把这一项换掉，再把它对应的那段住宿（stay 为空时新加一段）换成新酒店，
     * 价格、平台、预订链接用页面查到的，存成一个新版本。
     */
    private TripService.TripDetail replaceStay(UserCtx ctx, TripService.TripDetail detail, Itinerary itinerary,
                                               Found found, Itinerary.Item fresh, Itinerary.Stay stay, PlacePick place) {
        Itinerary.Item old = found.item();
        Itinerary.Day day = itinerary.days().get(found.day());
        Itinerary swapped = unbook(withDay(itinerary, found.day(), new Itinerary.Day(day.date(), day.theme(),
                items(itinerary, found.day(), old.id(), fresh), day.legs())), old.id());
        // 其他天写着同一家旧酒店的项（"回酒店休息"这类）也改成新酒店，去掉坐标，下面按新住宿重新关联
        swapped = swapped.withDays(swapped.days().stream().map(d -> new Itinerary.Day(d.date(), d.theme(),
                d.items().stream().map(i -> "HOTEL".equals(i.type()) && !i.id().equals(old.id())
                        && i.name().equals(old.name()) ? new Itinerary.Item(i.id(), i.start(), i.end(), i.type(),
                        place.name(), null, i.note(), i.cost(), null, i.locked()) : i).toList(), d.legs())).toList());
        StayPick hotel = cleanStay(new StayPick(place.name(), place.address(), place.lng(), place.lat(), place.photo(),
                place.rating(), place.price(), place.platform(), place.url(), place.quotes()));
        Itinerary updated = planStay(detail, swapped, stay == null ? null : stay.id(), hotel).itinerary();
        String note = "把「" + old.name() + "」换成「" + place.name() + "」" + (place.price() == null ? ""
                : "，每晚 ¥" + place.price() + "（" + place.platform() + "）");
        return tripService.saveVersion(ctx, detail.trip().getId(), new TripService.TripDraft(null, null, null, null,
                null, updated.toMarkdown(), note, json(updated), false), "USER", detail.version().getVersion());
    }

    /**
     * 一天路上的合计：分钟、公里、打车费（选了打车的路段，高德估的，都查不到为空）、步行段数、打车段数、最后一项结束时间，
     * 地铁公交段数和票价（元/人，都查不到为空）。
     */
    public record DayTotals(int minutes, double km, Integer taxi, int walks, int drives, String endsAt, int transits,
                            Double fare) {

        static DayTotals of(Itinerary.Day day) {
            List<Itinerary.Leg> legs = day.legs() == null ? List.of() : day.legs();
            int minutes = legs.stream().mapToInt(Itinerary.Leg::minutes).sum();
            double km = Math.round(legs.stream().mapToDouble(Itinerary.Leg::km).sum() * 10) / 10.0;
            List<Itinerary.Leg> drives = legs.stream().filter(l -> Itinerary.DRIVE.equals(l.mode())).toList();
            List<Itinerary.Leg> rides = legs.stream().filter(l -> Itinerary.TRANSIT.equals(l.mode())).toList();
            Integer taxi = drives.stream().anyMatch(l -> l.taxi() != null) ? drives.stream().map(Itinerary.Leg::taxi)
                    .filter(Objects::nonNull).mapToInt(Integer::intValue).sum() : null;
            Double fare = rides.stream().anyMatch(l -> l.transit().fee() != null) ? rides.stream()
                    .map(l -> l.transit().fee()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum() : null;
            int walks = (int) legs.stream().filter(l -> Itinerary.WALK.equals(l.mode())).count();
            String endsAt = day.items().stream().map(i -> i.end() != null ? i.end() : i.start()).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            return new DayTotals(minutes, km, taxi, walks, drives.size(), endsAt, rides.size(), fare);
        }
    }

    /**
     * 改动预览（不保存）：第几天（从 1 开始），改动前后的这一天（时间、路程都按高德算好）和前后合计。
     * changed 为 false 表示没必要改（比如顺序已经是最顺的）。lateBefore、lateAfter 是按高德实际路程排下来，
     * 吃饭、交通等固定安排一共会晚开始多少分钟（只有路线优化算）。
     */
    public record DayPreview(int day, Itinerary.Day before, Itinerary.Day after, DayTotals beforeTotals,
                             DayTotals afterTotals, boolean changed, int lateBefore, int lateAfter) {

        DayPreview(int day, Itinerary.Day before, Itinerary.Day after, DayTotals beforeTotals, DayTotals afterTotals,
                   boolean changed) {
            this(day, before, after, beforeTotals, afterTotals, changed, 0, 0);
        }
    }

    /** 优化目标：路上时间最短、里程最短、打车费最低（步行段不花钱，按打车里程算）。 */
    public enum Goal { TIME, DISTANCE, COST }

    /** 换之前先看看：换成选中的地点后，当天路上时间、里程、打车费怎么变。不保存。 */
    public DayPreview previewReplace(UserCtx ctx, long tripId, String itemId, PlacePick pick) {
        PlacePick place = clean(pick);
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = itinerary(detail);
        Found found = find(itinerary, itemId);
        String city = detail.trip().getDestination();
        Itinerary.Day day = itinerary.days().get(found.day());
        Itinerary.Day before = geo.relink(day, city, itinerary.stays());
        Itinerary.Day after = geo.relink(new Itinerary.Day(day.date(), day.theme(),
                items(itinerary, found.day(), itemId, replaced(found.item(), place))), city, itinerary.stays());
        return new DayPreview(found.day() + 1, before, after, DayTotals.of(before), DayTotals.of(after), true);
    }

    /**
     * 预览某天的路线优化（不保存）：锁定的、已预约的、吃饭、酒店、交通位置和时间都不动，
     * 其余景点按 goal 换顺序，路程全部用高德的数据算。
     */
    public DayPreview previewOptimize(UserCtx ctx, long tripId, int dayIndex, Goal goal) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return planOptimize(detail, itinerary(detail), dayIndex, goal);
    }

    /**
     * 确认优化：重新算一遍，结果和预览时的顺序（ids）一致才保存成新版本；行程在这期间被改过就让用户重新预览。
     */
    public TripService.TripDetail optimize(UserCtx ctx, long tripId, int dayIndex, Goal goal, List<String> ids) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            DayPreview plan = planOptimize(detail, itinerary, dayIndex, goal);
            if (!plan.changed()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这一天的顺序已经是最顺的了");
            }
            if (ids == null || !ids.equals(plan.after().items().stream().map(Itinerary.Item::id).toList())) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚有变化，请重新预览一次");
            }
            return save(ctx, detail, itinerary, dayIndex, plan.after().items(),
                    "优化第 " + (dayIndex + 1) + " 天路线（" + GOAL_NAMES.get(goal) + "）");
        });
    }

    private static final Map<Goal, String> GOAL_NAMES = Map.of(Goal.TIME, "时间最短", Goal.DISTANCE, "距离最短",
            Goal.COST, "费用最低");

    private DayPreview planOptimize(TripService.TripDetail detail, Itinerary itinerary, int dayIndex, Goal goal) {
        if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
            throw BizException.notFound("这一天");
        }
        if (!places.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置高德 Key，算不了路线");
        }
        String city = detail.trip().getDestination();
        List<Itinerary.Stay> stays = itinerary.stays();
        Itinerary.Day day = itinerary.days().get(dayIndex);
        Itinerary.Day before = geo.relink(day, city, stays);
        DayTotals was = DayTotals.of(before);
        Set<String> booked = itinerary.booked() == null ? Set.of() : Set.copyOf(itinerary.booked());
        Itinerary.Item start = home("@start", TripGeo.stayFor(stays, day.date(), "08:00"));
        Itinerary.Item end = home("@end", TripGeo.stayFor(stays, day.date(), "21:00"));
        RouteOptimizer.Metric metric = metric(before.items(), start, end, goal);
        RouteOptimizer.Result result = RouteOptimizer.optimize(before.items(), booked, start, end, metric);
        int dayStart = RouteOptimizer.startOf(before.items());
        Map<String, Integer> beforeLegs = legMinutes(before);
        int lateNow = RouteOptimizer.lateness(before.items(), booked, dayStart,
                (a, b) -> beforeLegs.getOrDefault(a.id() + ">" + b.id(), metric.minutes(a, b)));
        DayPreview same = new DayPreview(dayIndex + 1, before, before, was, was, false, lateNow, lateNow);
        if (!result.changed()) {
            return same;
        }
        Itinerary.Day draft = geo.relink(new Itinerary.Day(day.date(), day.theme(), result.items()), city, stays);
        Map<String, Integer> real = legMinutes(draft, before);
        java.util.function.ToIntBiFunction<Itinerary.Item, Itinerary.Item> travel = (a, b) ->
                real.getOrDefault(a.id() + ">" + b.id(), metric.minutes(a, b));
        List<Itinerary.Item> timed = RouteOptimizer.retime(draft.items(), booked, travel, dayStart);
        Itinerary.Day after = new Itinerary.Day(day.date(), day.theme(), timed, draft.legs());
        DayTotals now = DayTotals.of(after);
        // 按高德实际路程再核一遍：没变好、或者会让固定安排比原来更赶，就不建议改
        int lateAfter = RouteOptimizer.lateness(draft.items(), booked, dayStart, travel);
        int lateBefore = RouteOptimizer.lateness(before.items(), booked, dayStart, travel);
        boolean better = lateAfter <= lateBefore && switch (goal) {
            case TIME -> now.minutes() < was.minutes();
            case DISTANCE -> now.km() < was.km();
            case COST -> now.taxi() != null && was.taxi() != null ? now.taxi() < was.taxi() : now.km() < was.km();
        };
        return better ? new DayPreview(dayIndex + 1, before, after, was, now, true, lateBefore, lateAfter) : same;
    }

    /** 两天（改动前后）实际算出来的各段路上分钟数，键是"起点 id>终点 id"。 */
    private static Map<String, Integer> legMinutes(Itinerary.Day... days) {
        Map<String, Integer> out = new HashMap<>();
        for (Itinerary.Day day : days) {
            for (Itinerary.Leg leg : day.legs() == null ? List.<Itinerary.Leg>of() : day.legs()) {
                out.putIfAbsent(leg.from() + ">" + leg.to(), leg.minutes());
            }
        }
        return out;
    }

    /** 住处当成一个虚拟的点（只用坐标），没有或没坐标返回空。 */
    private static Itinerary.Item home(String id, Itinerary.Stay stay) {
        if (stay == null || !stay.located()) {
            return null;
        }
        return new Itinerary.Item(id, null, null, "HOTEL", stay.name(), null, null, null, null, null, stay.poiId(),
                stay.lng(), stay.lat(), null, null);
    }

    /**
     * 用高德驾车距离矩阵给优化打分：1.2 公里内按步行（和行程里的路程算法一致），远的用高德的驾车时间和里程。
     * 高德查不到时退回按直线估算。
     */
    private RouteOptimizer.Metric metric(List<Itinerary.Item> items, Itinerary.Item start, Itinerary.Item end,
                                         Goal goal) {
        List<Itinerary.Item> points = new ArrayList<>(items.stream().filter(Itinerary.Item::located).toList());
        if (start != null) {
            points.add(start);
        }
        if (end != null) {
            points.add(end);
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            index.putIfAbsent(points.get(i).id(), i);
        }
        long[][][] matrix;
        try {
            matrix = places.drivingMatrix(points.stream().map(p -> new double[]{p.lng(), p.lat()}).toList());
        } catch (RuntimeException e) {
            log.warn("高德距离矩阵查询失败，按直线估算：{}", e.getMessage());
            matrix = null;
        }
        long[][][] m = matrix;
        return new RouteOptimizer.Metric() {
            /** {分钟, 公里, 是否步行} */
            private double[] leg(Itinerary.Item a, Itinerary.Item b) {
                double straight = TripGeo.distanceKm(a.lat(), a.lng(), b.lat(), b.lng());
                if (straight <= 1.2) {
                    return new double[]{Math.max(1, Math.round(straight * 1.3 / 4.5 * 60)), straight * 1.3, 1};
                }
                Integer i = index.get(a.id());
                Integer j = index.get(b.id());
                long[] cell = m == null || i == null || j == null ? null : m[i][j];
                if (cell == null) {
                    return new double[]{RouteOptimizer.estimate(a, b), straight * 1.4, 0};
                }
                return new double[]{Math.max(1, Math.round(cell[0] / 60.0)), cell[1] / 1000.0, 0};
            }

            @Override
            public double weight(Itinerary.Item a, Itinerary.Item b) {
                double[] l = leg(a, b);
                return switch (goal) {
                    case TIME -> l[0];
                    case DISTANCE -> l[1];
                    // 步行不花钱；打车费大致跟里程走，加一点时间免得同价时绕远
                    case COST -> (l[2] == 1 ? 0 : l[1]) + l[0] * 0.01;
                };
            }

            @Override
            public int minutes(Itinerary.Item a, Itinerary.Item b) {
                return (int) leg(a, b)[0];
            }

            @Override
            public double minSaving() {
                return goal == Goal.TIME ? 5 : 1;
            }
        };
    }

    /** 删掉某项，重算当天路程。 */
    public TripService.TripDetail remove(UserCtx ctx, long tripId, String itemId, Reason reason) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            Found found = find(itinerary, itemId);
            remember(ctx, found.item().name(), reason);
            return save(ctx, detail, unbook(itinerary, itemId), found.day(), items(itinerary, found.day(), itemId, null),
                    "删掉「" + found.item().name() + "」" + (reason == Reason.DISLIKE ? "（不感兴趣）" : reason == Reason.VISITED ? "（去过了）" : ""));
        });
    }

    /** 标记保留或取消保留；只改当前版本。 */
    public TripService.TripDetail lock(UserCtx ctx, long tripId, String itemId, boolean locked) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            Found found = find(itinerary, itemId);
            Itinerary.Item i = found.item();
            Itinerary.Item changed = new Itinerary.Item(i.id(), i.start(), i.end(), i.type(), i.name(), i.address(),
                    i.note(), i.cost(), i.url(), locked ? Boolean.TRUE : null, i.poiId(), i.lng(), i.lat(), i.photo(),
                    i.rating());
            Itinerary updated = withDay(itinerary, found.day(), new Itinerary.Day(itinerary.days().get(found.day()).date(),
                    itinerary.days().get(found.day()).theme(), items(itinerary, found.day(), itemId, changed),
                    itinerary.days().get(found.day()).legs()));
            String json = json(updated);
            tripService.updateItinerary(tripId, detail.version().getVersion(), json);
            detail.version().setItinerary(json);
            return detail;
        });
    }

    /**
     * 标记已预约或取消：targetId 是某项安排（d1-3）或某段住宿（s1）的 id。用户自己在平台上订好后标记，
     * 只改当前版本，不算新版本；正文里也会写上"已预约"。
     */
    public TripService.TripDetail markBooked(UserCtx ctx, long tripId, String targetId, boolean booked) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            boolean isStay = itinerary.stays() != null && itinerary.stays().stream().anyMatch(st -> st.id().equals(targetId));
            if (!isStay) {
                find(itinerary, targetId);
            }
            List<String> ids = new ArrayList<>(itinerary.booked() == null ? List.of() : itinerary.booked());
            ids.remove(targetId);
            if (booked) {
                ids.add(targetId);
            }
            Itinerary updated = itinerary.withBooked(ids);
            String json = json(updated);
            String content = updated.toMarkdown();
            tripService.updateItinerary(tripId, detail.version().getVersion(), json, content);
            detail.version().setItinerary(json);
            detail.version().setContent(content);
            return detail;
        });
    }

    /** 换掉、删掉的项不再算已预约，也不再算去过。 */
    private static Itinerary unbook(Itinerary itinerary, String id) {
        if (itinerary.isDone(id)) {
            List<String> done = new ArrayList<>(itinerary.done());
            done.remove(id);
            itinerary = itinerary.withDone(done);
        }
        // 换掉的项沿用原来的 id，"已跳转平台"的记录也要一起清掉
        if (itinerary.bookingStatus() != null && itinerary.bookingStatus().containsKey(id)) {
            Map<String, String> status = new HashMap<>(itinerary.bookingStatus());
            status.remove(id);
            itinerary = itinerary.withBookingStatus(status);
        }
        if (!itinerary.isBooked(id)) {
            return itinerary;
        }
        List<String> ids = new ArrayList<>(itinerary.booked());
        ids.remove(id);
        return itinerary.withBooked(ids);
    }

    /**
     * 设置某项（安排或住宿）的预订状态：PLANNED 计划预订、OPENED 已跳转平台（点过预订链接，还不算订好）、
     * CONFIRMED 已确认订单（用户自己确认订好了）。只改当前版本，不算新版本。
     */
    public TripService.TripDetail setBooking(UserCtx ctx, long tripId, String targetId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!Set.of(Itinerary.PLANNED, Itinerary.OPENED, Itinerary.CONFIRMED).contains(s)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "预订状态不对");
        }
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            boolean isStay = itinerary.stays() != null && itinerary.stays().stream().anyMatch(st -> st.id().equals(targetId));
            if (!isStay) {
                find(itinerary, targetId);
            }
            // 已确认订好的不会因为又点了一次预订链接就退回"已跳转"
            if (Itinerary.OPENED.equals(s) && itinerary.isBooked(targetId)) {
                return detail;
            }
            List<String> ids = new ArrayList<>(itinerary.booked() == null ? List.of() : itinerary.booked());
            ids.remove(targetId);
            if (Itinerary.CONFIRMED.equals(s)) {
                ids.add(targetId);
            }
            Map<String, String> opened = new HashMap<>(itinerary.bookingStatus() == null ? Map.of() : itinerary.bookingStatus());
            opened.remove(targetId);
            if (Itinerary.OPENED.equals(s)) {
                opened.put(targetId, Itinerary.OPENED);
            }
            Itinerary updated = itinerary.withBooked(ids).withBookingStatus(opened);
            String json = json(updated);
            String content = updated.toMarkdown();
            tripService.updateItinerary(tripId, detail.version().getVersion(), json, content);
            detail.version().setItinerary(json);
            detail.version().setContent(content);
            return detail;
        });
    }

    /** 标记某个安排去过了 / 吃过了（done 为 false 取消）。只改当前版本，不算新版本。 */
    public TripService.TripDetail setDone(UserCtx ctx, long tripId, String itemId, boolean done) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            find(itinerary, itemId);
            if (itinerary.isDone(itemId) == done) {
                return detail;
            }
            List<String> ids = new ArrayList<>(itinerary.done() == null ? List.of() : itinerary.done());
            if (done) {
                ids.add(itemId);
            } else {
                ids.remove(itemId);
            }
            Itinerary updated = itinerary.withDone(ids);
            String json = json(updated);
            String content = updated.toMarkdown();
            tripService.updateItinerary(tripId, detail.version().getVersion(), json, content);
            detail.version().setItinerary(json);
            detail.version().setContent(content);
            return detail;
        });
    }

    /** 改整个行程默认怎么走（TRANSIT 地铁公交 / DRIVE 打车），单独改过的路段一起清掉。只改当前版本，不算新版本。 */
    public TripService.TripDetail setTransport(UserCtx ctx, long tripId, String transport) {
        String mode = checkMode(transport);
        return saveTransport(ctx, tripId, it -> it.withTransport(mode, null));
    }

    /** 单独改一段路怎么走；和行程默认一样就不单独记。只改当前版本，不算新版本。 */
    public TripService.TripDetail setLegMode(UserCtx ctx, long tripId, String from, String to, String mode) {
        String wanted = checkMode(mode);
        return saveTransport(ctx, tripId, it -> {
            boolean exists = it.days().stream().filter(d -> d.legs() != null).flatMap(d -> d.legs().stream())
                    .anyMatch(l -> l.from().equals(from) && l.to().equals(to));
            if (!exists) {
                throw new BizException(ErrorCode.NOT_FOUND, "没找到这段路");
            }
            Map<String, String> modes = new HashMap<>(it.legModes() == null ? Map.of() : it.legModes());
            String key = from + ">" + to;
            if (wanted.equals(Itinerary.DRIVE.equals(it.transport()) ? Itinerary.DRIVE : Itinerary.TRANSIT)) {
                modes.remove(key);
            } else {
                modes.put(key, wanted);
            }
            return it.withTransport(it.transport(), modes);
        });
    }

    private static String checkMode(String mode) {
        if (!Itinerary.TRANSIT.equals(mode) && !Itinerary.DRIVE.equals(mode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "出行方式只能是 TRANSIT 或 DRIVE");
        }
        return mode;
    }

    private TripService.TripDetail saveTransport(UserCtx ctx, long tripId, java.util.function.UnaryOperator<Itinerary> change) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary updated = change.apply(itinerary(detail));
            String json = json(updated);
            String content = updated.toMarkdown();
            tripService.updateItinerary(tripId, detail.version().getVersion(), json, content);
            detail.version().setItinerary(json);
            detail.version().setContent(content);
            return detail;
        });
    }

    /**
     * 复制行程：当前版本的逐日安排存成一个新的草稿行程，标题加"（副本）"，预订状态全部清空；
     * 给了 newStart 就把所有日期整体平移到从那天开始。
     */
    public TripService.TripDetail copy(UserCtx ctx, long tripId, LocalDate newStart) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Trip trip = detail.trip();
        Itinerary itinerary = parse(detail.version().getItinerary());
        long shift = newStart != null && trip.getStartDate() != null
                ? java.time.temporal.ChronoUnit.DAYS.between(trip.getStartDate(), newStart) : 0;
        String json = null;
        String content = detail.version().getContent();
        if (itinerary != null && itinerary.days() != null) {
            List<Itinerary.Day> days = itinerary.days().stream().map(d -> new Itinerary.Day(shiftDate(d.date(), shift),
                    d.theme(), d.items(), d.legs())).toList();
            List<Itinerary.Stay> stays = itinerary.stays() == null ? null : itinerary.stays().stream()
                    .map(st -> new Itinerary.Stay(st.id(), st.name(), shiftDate(st.checkIn(), shift),
                            shiftDate(st.checkOut(), shift), st.address(), st.price(), st.platform(), st.url(),
                            st.queriedAt(), st.quotes(), st.options(), st.poiId(), st.lng(), st.lat(), st.photo(),
                            st.rating())).toList();
            Itinerary copied = new Itinerary(days, itinerary.tips(), itinerary.geo(), stays, null, null, null,
                    itinerary.transport(), itinerary.legModes());
            json = json(copied);
            content = copied.toMarkdown();
        }
        LocalDate start = trip.getStartDate() == null ? null : trip.getStartDate().plusDays(shift);
        LocalDate end = trip.getEndDate() == null ? null : trip.getEndDate().plusDays(shift);
        return tripService.saveVersion(ctx, null, new TripService.TripDraft(cut(trip.getTitle(), 90) + "（副本）",
                trip.getTripType(), trip.getDestination(), start, end, content, "复制自「" + trip.getTitle() + "」",
                json, true), "USER");
    }

    private static String shiftDate(String date, long days) {
        if (date == null || days == 0) {
            return date;
        }
        try {
            return LocalDate.parse(date).plusDays(days).toString();
        } catch (RuntimeException e) {
            return date;
        }
    }

    /** 恢复到某个历史版本：把那一版的内容存成新版本，历史不删。 */
    public TripService.TripDetail restore(UserCtx ctx, long tripId, int version) {
        return locked(tripId, () -> {
            TripService.TripDetail current = tripService.get(ctx, tripId, null);
            if (current.version().getVersion() == version) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这已经是当前版本了");
            }
            TripService.TripDetail old = tripService.get(ctx, tripId, version);
            return tripService.saveVersion(ctx, tripId, new TripService.TripDraft(null, null, null, null, null,
                    old.version().getContent(), "恢复到第 " + version + " 版", old.version().getItinerary(), false), "USER",
                    current.version().getVersion());
        });
    }

    /**
     * 调整某天的顺序：ids 是这一天全部项的新顺序。原来的时间段按先后不动，依次分给新顺序里的项。
     */
    public TripService.TripDetail reorder(UserCtx ctx, long tripId, int dayIndex, List<String> ids) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
                throw BizException.notFound("这一天");
            }
            List<Itinerary.Item> old = itinerary.days().get(dayIndex).items();
            if (ids == null || ids.size() != old.size() || !Set.copyOf(ids).equals(old.stream().map(Itinerary.Item::id)
                    .collect(Collectors.toSet()))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "顺序里的项和这一天的安排对不上，刷新后再试");
            }
            List<Itinerary.Item> reordered = new ArrayList<>();
            for (int k = 0; k < ids.size(); k++) {
                String id = ids.get(k);
                Itinerary.Item i = old.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                Itinerary.Item slot = old.get(k);
                reordered.add(new Itinerary.Item(i.id(), slot.start(), slot.end(), i.type(), i.name(), i.address(),
                        i.note(), i.cost(), i.url(), i.locked(), i.poiId(), i.lng(), i.lat(), i.photo(), i.rating()));
            }
            return save(ctx, detail, itinerary, dayIndex, reordered, "调整第 " + (dayIndex + 1) + " 天的顺序");
        });
    }

    /**
     * 把一个地点加进某天：吃饭放到午饭（12:00）或晚饭（18:00）里还空着的那顿，其他的接在当天最后一项后面。
     */
    public TripService.TripDetail add(UserCtx ctx, long tripId, int dayIndex, PlacePick pick) {
        PlacePick place = clean(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
                throw BizException.notFound("这一天");
            }
            List<Itinerary.Item> items = new ArrayList<>(itinerary.days().get(dayIndex).items());
            if (items.size() >= MAX_ITEMS_PER_DAY) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这一天的安排太多了，先删掉几项");
            }
            String type = place.type() == null ? "SPOT" : place.type();
            String[] slot = slot(items, type);
            int next = items.stream().map(i -> i.id().replaceAll(".*-", "")).filter(s -> s.matches("\\d+"))
                    .mapToInt(Integer::parseInt).max().orElse(0) + 1;
            items.add(new Itinerary.Item("d" + (dayIndex + 1) + "-" + next, slot[0], slot[1], type, place.name(),
                    place.address(), place.note(), place.cost(), null, null, place.poiId(), place.lng(), place.lat(), place.photo(),
                    place.rating()));
            items.sort(Comparator.comparing(i -> i.start() == null ? "99:99" : i.start()));
            return save(ctx, detail, itinerary, dayIndex, items, "第 " + (dayIndex + 1) + " 天加入「" + place.name() + "」");
        });
    }

    /** 一顿饭：名称、时间窗口（在这段时间里开始的吃饭项就算这一顿）、平常几点吃、默认吃多久（分钟）。 */
    public enum Meal {
        BREAKFAST("早餐", LocalTime.of(6, 30), LocalTime.of(10, 0), LocalTime.of(7, 30), 40),
        LUNCH("午餐", LocalTime.of(11, 0), LocalTime.of(14, 30), LocalTime.of(12, 0), 60),
        DINNER("晚餐", LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(18, 0), 75);

        final String label;
        final LocalTime from;
        final LocalTime to;
        /** 插进空档时尽量从这个时间开始 */
        final LocalTime usual;
        final int minutes;

        Meal(String label, LocalTime from, LocalTime to, LocalTime usual, int minutes) {
            this.label = label;
            this.from = from;
            this.to = to;
            this.usual = usual;
            this.minutes = minutes;
        }

        /** 这一项是不是这一顿：吃饭类，开始时间落在窗口里。 */
        boolean holds(Itinerary.Item i) {
            if (!"FOOD".equals(i.type()) || i.start() == null) {
                return false;
            }
            LocalTime t = time(i.start());
            return !t.isBefore(from) && t.isBefore(to);
        }
    }

    /** 加入后会晚到的一项：比原来多晚 minutes 分钟到；fixed 表示用户保留或已预约的。 */
    public record Impact(String id, String name, int minutes, boolean fixed) {
    }

    /**
     * 把餐厅放进某一顿的预览（不保存）。这一顿原来有饭就换掉（replacesId），没有就插进空档。
     * prevName、nextName 是前后挨着的安排；foodBefore、foodAfter 是当天吃饭人均合计（元，没写人均的不算）；
     * impacts 是因为多走路、多吃饭会比原来晚到的安排；notes 是路程合计没算到、需要用户自己确认的地方。
     */
    public record MealPreview(DayPreview preview, Meal meal, String itemId, String start, String end,
                              String replacesId, String replacesName, String prevName, String nextName,
                              int foodBefore, int foodAfter, List<Impact> impacts, List<String> notes) {
    }

    /** 预览把餐厅放进第 dayIndex 天的某一顿，路程按高德算。 */
    public MealPreview previewMeal(UserCtx ctx, long tripId, int dayIndex, Meal meal, PlacePick pick) {
        PlacePick place = clean(pick);
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return planMeal(detail, itinerary(detail), dayIndex, meal, place);
    }

    /**
     * 确认放进这一顿：重新算一遍，要替换的项和预览时一致（replacesId，没有替换传空）才保存成新版本。
     */
    public TripService.TripDetail saveMeal(UserCtx ctx, long tripId, int dayIndex, Meal meal, PlacePick pick,
                                           String replacesId) {
        PlacePick place = clean(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            MealPreview plan = planMeal(detail, itinerary, dayIndex, meal, place);
            if (!Objects.equals(blankToNull(replacesId), plan.replacesId())) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚有变化，请重新预览一次");
            }
            String note = "第 " + (dayIndex + 1) + " 天" + meal.label + (plan.replacesId() == null
                    ? "加入「" + place.name() + "」" : "从「" + plan.replacesName() + "」换成「" + place.name() + "」");
            Itinerary base = plan.replacesId() == null ? itinerary : unbook(itinerary, plan.replacesId());
            return save(ctx, detail, base, dayIndex, plan.preview().after().items(), note);
        });
    }

    private MealPreview planMeal(TripService.TripDetail detail, Itinerary itinerary, int dayIndex, Meal meal,
                                 PlacePick place) {
        if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
            throw BizException.notFound("这一天");
        }
        Itinerary.Day day = itinerary.days().get(dayIndex);
        List<Itinerary.Item> items = new ArrayList<>(day.items());
        Itinerary.Item old = items.stream().filter(meal::holds).findFirst().orElse(null);
        Itinerary.Item fresh;
        if (old != null) {
            if (Boolean.TRUE.equals(old.locked()) || itinerary.isBooked(old.id())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这天的" + meal.label + "「" + old.name() + "」"
                        + (itinerary.isBooked(old.id()) ? "已预约" : "你设了保留") + "，先取消再换");
            }
            fresh = new Itinerary.Item(old.id(), old.start(), old.end(), "FOOD", place.name(), place.address(),
                    place.note(), place.cost(), null, null, place.poiId(), place.lng(), place.lat(), place.photo(),
                    place.rating());
            items.set(items.indexOf(old), fresh);
        } else {
            if (items.size() >= MAX_ITEMS_PER_DAY) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这一天的安排太多了，先删掉几项");
            }
            LocalTime[] gap = gap(items, meal.from, meal.to, Math.min(45, meal.minutes));
            if (gap == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这天" + meal.label + "时间（" + meal.from + "–" + meal.to
                        + "）都排满了，先删掉或缩短一项，或者换掉已有的安排");
            }
            // 空档里尽量靠近平常吃饭的时间，放不下就从空档开头开始
            LocalTime latest = gap[1].minusMinutes(meal.minutes);
            LocalTime begin = latest.isBefore(gap[0]) ? gap[0] : meal.usual.isBefore(gap[0]) ? gap[0]
                    : meal.usual.isAfter(latest) ? latest : meal.usual;
            gap = new LocalTime[]{begin, gap[1]};
            LocalTime end = gap[1].isAfter(gap[0].plusMinutes(meal.minutes)) ? gap[0].plusMinutes(meal.minutes) : gap[1];
            int next = items.stream().map(i -> i.id().replaceAll(".*-", "")).filter(s -> s.matches("\\d+"))
                    .mapToInt(Integer::parseInt).max().orElse(0) + 1;
            fresh = new Itinerary.Item("d" + (dayIndex + 1) + "-" + next, gap[0].toString(), end.toString(), "FOOD",
                    place.name(), place.address(), place.note(), place.cost(), null, null, place.poiId(), place.lng(),
                    place.lat(), place.photo(), place.rating());
            items.add(fresh);
            items.sort(Comparator.comparing(i -> i.start() == null ? "99:99" : i.start()));
        }
        String city = detail.trip().getDestination();
        Itinerary.Day before = geo.relink(day, city, itinerary.stays());
        Itinerary.Day after = geo.relink(new Itinerary.Day(day.date(), day.theme(), items), city, itinerary.stays());
        int at = items.indexOf(fresh);
        String prevName = at > 0 ? items.get(at - 1).name() : null;
        String nextName = at + 1 < items.size() ? items.get(at + 1).name() : null;
        Map<String, Integer> lateBefore = lateArrivals(before);
        List<Impact> impacts = new ArrayList<>();
        lateArrivals(after).forEach((id, late) -> {
            if (late > lateBefore.getOrDefault(id, 0)) {
                Itinerary.Item i = after.items().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                impacts.add(new Impact(id, i.name(), late, Boolean.TRUE.equals(i.locked()) || itinerary.isBooked(id)));
            }
        });
        return new MealPreview(new DayPreview(dayIndex + 1, before, after, DayTotals.of(before), DayTotals.of(after), true),
                meal, fresh.id(), fresh.start(), fresh.end(), old == null ? null : old.id(), old == null ? null : old.name(),
                prevName, nextName, foodCost(before), foodCost(after), impacts, mealNotes(items, at, old, fresh,
                        itinerary.stays(), day.date()));
    }

    /**
     * 路程合计没覆盖到的地方：换的餐厅离原来那家远、前后挨着的交通安排没跟着改、吃完是当天最后一个有坐标的地方
     * （回住处的路没算进合计）。
     */
    private List<String> mealNotes(List<Itinerary.Item> items, int at, Itinerary.Item old, Itinerary.Item fresh,
                                   List<Itinerary.Stay> stays, String date) {
        List<String> notes = new ArrayList<>();
        if (old != null && old.located() && fresh.located()) {
            double km = TripGeo.distanceKm(old.lat(), old.lng(), fresh.lat(), fresh.lng());
            if (km > 3) {
                notes.add("新餐厅离原来那家约 " + Math.round(km) + " 公里（直线），前后的路线会变化比较大");
            }
        }
        for (int k : new int[]{at - 1, at + 1}) {
            if (k >= 0 && k < items.size() && "TRANSPORT".equals(items.get(k).type()) && !items.get(k).located()) {
                notes.add("挨着的交通安排「" + items.get(k).name() + "」没有跟着调整，确认一下还需不需要");
            }
        }
        boolean lastPlace = items.subList(at + 1, items.size()).stream().noneMatch(Itinerary.Item::located);
        if (lastPlace && fresh.located()) {
            Itinerary.Stay stay = TripGeo.stayFor(stays, date, "21:00");
            Integer back = null;
            if (stay != null && stay.located()) {
                try {
                    back = places.driving(new double[]{fresh.lng(), fresh.lat()}, new double[]{stay.lng(), stay.lat()})
                            .map(AmapPlaceService.Route::minutes).orElse(null);
                } catch (RuntimeException e) {
                    log.warn("查回住处的路线失败：{}", e.getMessage());
                }
            }
            notes.add(back != null ? "吃完回「" + stay.name() + "」打车约 " + back + " 分钟（高德），不在上面的合计里"
                    : "这是当天最后一个有位置的地方，吃完回住处的路没算进上面的合计");
        }
        return notes;
    }

    /**
     * 按高德算的路程，相邻两项之间来不及的：上一项结束（没写结束按开始）加路上时间，晚于下一项开始多少分钟。
     * 只看挨着的两项都有坐标的情况。
     */
    static Map<String, Integer> lateArrivals(Itinerary.Day day) {
        Map<String, Integer> legs = legMinutes(day);
        Map<String, Integer> out = new HashMap<>();
        List<Itinerary.Item> items = day.items();
        for (int k = 1; k < items.size(); k++) {
            Itinerary.Item a = items.get(k - 1);
            Itinerary.Item b = items.get(k);
            Integer leg = legs.get(a.id() + ">" + b.id());
            String leave = a.end() != null ? a.end() : a.start();
            if (leg == null || leave == null || b.start() == null) {
                continue;
            }
            int late = (int) java.time.Duration.between(time(b.start()), time(leave).plusMinutes(leg)).toMinutes();
            if (late > 0) {
                out.put(b.id(), late);
            }
        }
        return out;
    }

    /** 当天吃饭的人均合计（元）。 */
    private static int foodCost(Itinerary.Day day) {
        return day.items().stream().filter(i -> "FOOD".equals(i.type()) && i.cost() != null)
                .mapToInt(Itinerary.Item::cost).sum();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 比价页上选的航班：航班号、航司、起降时间（HH:mm）、机场、价格、哪个平台、预订链接。 */
    public record FlightPick(String flightNo, String airline, String depTime, String arrTime, String depAirport,
                             String arrAirport, Integer price, String platform, String url) {
    }

    /** 和新航班冲突的一项：problem 是原因；fixed 表示用户保留或已预约的。 */
    public record FlightConflict(String id, String name, String problem, boolean fixed) {
    }

    /**
     * 把航班写进某天的预览（不保存）：item 是写进去的那一项；这天原来有航班就换掉（replacesId），没有就新加。
     * conflicts 是时间对不上的安排，notes 是需要用户自己确认的地方。
     */
    public record FlightPreview(int day, Itinerary.Item item, String replacesId, String replacesName,
                                String replacesTime, List<FlightConflict> conflicts, List<String> notes) {
    }

    /** 用户上传的订单记进来的航班、住宿，平台写这个 */
    public static final String ORDER = "订单";

    private static final java.util.regex.Pattern FLIGHT_NO = java.util.regex.Pattern.compile("[A-Z0-9]{2}\\d{1,4}(/[A-Z0-9]{2}\\d{1,4})*");
    /** 安排名称里带航班号的交通项就当成这天的航班 */
    private static final java.util.regex.Pattern FLIGHT_IN_NAME = java.util.regex.Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{2}\\d{3,4}(?!\\d)");
    private static final java.util.regex.Pattern HHMM = java.util.regex.Pattern.compile("\\d{1,2}:\\d{2}");

    public FlightPreview previewFlight(UserCtx ctx, long tripId, int dayIndex, FlightPick pick) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return planFlight(itinerary(detail), dayIndex, cleanFlight(pick)).preview();
    }

    /** 确认写进行程：重新算一遍，要换掉的项和预览时一致才保存成新版本。不会帮用户下单。 */
    public TripService.TripDetail saveFlight(UserCtx ctx, long tripId, int dayIndex, FlightPick pick, String replacesId) {
        FlightPick flight = cleanFlight(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            FlightPlan plan = planFlight(itinerary, dayIndex, flight);
            if (!Objects.equals(blankToNull(replacesId), plan.preview().replacesId())) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚有变化，请重新预览一次");
            }
            String note = "第 " + (dayIndex + 1) + " 天航班" + (plan.preview().replacesId() == null ? "加入" : "换成")
                    + "「" + flight.flightNo() + " " + flight.depTime() + "–" + flight.arrTime() + "」"
                    + (flight.price() == null ? "" : "（" + flight.platform() + " ¥" + flight.price() + "）");
            Itinerary base = plan.preview().replacesId() == null ? itinerary : unbook(itinerary, plan.preview().replacesId());
            return save(ctx, detail, base, dayIndex, plan.items(), note);
        });
    }

    private record FlightPlan(FlightPreview preview, List<Itinerary.Item> items) {
    }

    private FlightPlan planFlight(Itinerary itinerary, int dayIndex, FlightPick f) {
        if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
            throw BizException.notFound("这一天");
        }
        Itinerary.Day day = itinerary.days().get(dayIndex);
        List<Itinerary.Item> items = new ArrayList<>(day.items());
        Itinerary.Item old = items.stream().filter(i -> "TRANSPORT".equals(i.type()) && i.name() != null
                && FLIGHT_IN_NAME.matcher(i.name()).find()).findFirst().orElse(null);
        if (old != null && (Boolean.TRUE.equals(old.locked()) || itinerary.isBooked(old.id()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这天的航班「" + old.name() + "」"
                    + (itinerary.isBooked(old.id()) ? "已预约" : "你设了保留") + "，先取消再换");
        }
        LocalTime dep = time(f.depTime());
        LocalTime arr = time(f.arrTime());
        boolean nextDay = arr.isBefore(dep);
        String end = nextDay ? "23:59" : f.arrTime();
        String name = (f.airline() == null ? "" : f.airline() + " ") + f.flightNo() + " "
                + (f.depAirport() == null ? "" : f.depAirport()) + " → " + (f.arrAirport() == null ? "" : f.arrAirport())
                + (nextDay ? "（次日 " + f.arrTime() + " 到）" : "");
        String note = ORDER.equals(f.platform()) ? "已出票（用户上传的订单）" + (f.price() == null ? "" : "，票价 ¥" + f.price())
                : (f.price() == null ? "" : f.platform() + " ¥" + f.price() + "，")
                + LocalDate.now() + " 比价，价格以平台为准；订好后回来标记已预约";
        String id = old != null ? old.id() : "d" + (dayIndex + 1) + "-" + (items.stream().map(i -> i.id().replaceAll(".*-", ""))
                .filter(x -> x.matches("\\d+")).mapToInt(Integer::parseInt).max().orElse(0) + 1);
        Itinerary.Item fresh = new Itinerary.Item(id, f.depTime(), end, "TRANSPORT", cut(name.trim(), MAX_NAME), null,
                note, f.price(), f.url(), null);
        if (old != null) {
            items.set(items.indexOf(old), fresh);
        } else {
            if (items.size() >= MAX_ITEMS_PER_DAY) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这一天的安排太多了，先删掉几项");
            }
            items.add(fresh);
        }
        items.sort(Comparator.comparing(i -> i.start() == null ? "99:99" : (i.start().length() == 4 ? "0" : "") + i.start()));
        LocalTime flightEnd = nextDay ? LocalTime.MAX : arr;
        List<FlightConflict> conflicts = new ArrayList<>();
        for (Itinerary.Item i : items) {
            if (i == fresh || i.start() == null || !HHMM.matcher(i.start()).matches()) {
                continue;
            }
            LocalTime s = time(i.start());
            LocalTime e = i.end() != null && HHMM.matcher(i.end()).matches() ? time(i.end()) : s.plusMinutes(30);
            boolean fixed = Boolean.TRUE.equals(i.locked()) || itinerary.isBooked(i.id());
            boolean transport = "TRANSPORT".equals(i.type());
            String problem = null;
            if (s.isBefore(flightEnd) && e.isAfter(dep)) {
                problem = "和航班时间（" + f.depTime() + "–" + f.arrTime() + "）重叠";
            } else if (!transport && !nextDay && !s.isBefore(arr) && s.isBefore(arr.plusMinutes(60))) {
                problem = "落地后只有 " + java.time.Duration.between(arr, s).toMinutes() + " 分钟，出机场再赶过去一般要 1 小时以上";
            } else if (!transport && !e.isAfter(dep) && e.isAfter(dep.minusMinutes(120))) {
                problem = "离起飞只有 " + java.time.Duration.between(e, dep).toMinutes() + " 分钟，国内航班一般要提前 1.5–2 小时到机场";
            }
            if (problem != null) {
                conflicts.add(new FlightConflict(i.id(), i.name(), problem, fixed));
            }
        }
        List<String> notes = new ArrayList<>();
        if (old == null) {
            notes.add("这天原来没有航班，会新加一项");
        } else if (!Objects.equals(old.start(), fresh.start()) || !Objects.equals(old.end(), fresh.end())) {
            items.stream().filter(i -> i != fresh && "TRANSPORT".equals(i.type())
                            && conflicts.stream().noneMatch(c -> c.id().equals(i.id())))
                    .forEach(i -> notes.add("「" + i.name() + "」的时间没有跟着改，记得一起调整"));
        }
        notes.add("这一步只改行程，不会下单；价格随时会变，以平台页面为准");
        FlightPreview preview = new FlightPreview(dayIndex + 1, fresh, old == null ? null : old.id(),
                old == null ? null : old.name(), old == null ? null : old.start() + "–" + old.end(), conflicts, notes);
        return new FlightPlan(preview, items);
    }

    /** 检查页面传来的航班：航班号、时间格式，链接只收 http(s)。 */
    private static FlightPick cleanFlight(FlightPick f) {
        if (f == null || f.flightNo() == null || !FLIGHT_NO.matcher(f.flightNo().trim()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "航班号不对");
        }
        if (f.depTime() == null || f.arrTime() == null || !HHMM.matcher(f.depTime()).matches()
                || !HHMM.matcher(f.arrTime()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "起降时间不对");
        }
        String url = f.url() == null || !f.url().matches("https?://\\S{1,500}") ? null : f.url();
        return new FlightPick(f.flightNo().trim(), cut(f.airline(), 20), pad(f.depTime()), pad(f.arrTime()),
                cut(f.depAirport(), 30), cut(f.arrAirport(), 30), f.price(), cut(f.platform(), 10), url);
    }

    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    /**
     * 比价页上选的酒店：名称、地址、坐标、评分、每晚价格和平台、预订链接、各平台报价（有就带上）。
     * 照片只收高德图床的，其他平台的不存（导出 PDF 时服务器会下载照片）。
     */
    public record StayPick(String name, String address, Double lng, Double lat, String photo, Double rating,
                           Integer price, String platform, String url, List<Itinerary.StayQuote> quotes) {
    }

    /** 换酒店影响的一天：改之前、之后这天路上的合计（高德）。 */
    public record StayDay(int day, String date, DayTotals before, DayTotals after) {
    }

    /**
     * 换（或加上）住宿的预览：stay 是换上去的住宿；replacesId、replacesName 是被换掉的那段（行程原来没有住宿时为空，
     * 会新加一段覆盖整个行程）；days 是受影响的每一天路程前后对比；notes 是需要用户知道的地方。
     */
    public record StayPreview(Itinerary.Stay stay, String replacesId, String replacesName, Integer oldPrice,
                              int nights, List<StayDay> days, List<String> notes) {
    }

    private record StayPlan(StayPreview preview, Itinerary itinerary) {
    }

    /** 预览把选中的酒店换进行程（stayId 为空：行程只有一段住宿就换那段，没有住宿就新加一段）。不保存。 */
    public StayPreview previewStay(UserCtx ctx, long tripId, String stayId, StayPick pick) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return planStay(detail, itinerary(detail), stayId, cleanStay(pick)).preview();
    }

    /** 确认换酒店：重新算一遍，被换掉的那段和预览时一致才保存成新版本；不下单。 */
    public TripService.TripDetail saveStay(UserCtx ctx, long tripId, String stayId, StayPick pick, String replacesId) {
        StayPick hotel = cleanStay(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            StayPlan plan = planStay(detail, itinerary(detail), stayId, hotel);
            StayPreview p = plan.preview();
            if (!Objects.equals(blankToNull(replacesId), p.replacesId())) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚有变化，请重新预览一次");
            }
            String note = p.replacesName() == null ? "住宿定为「" + hotel.name() + "」"
                    : "住宿从「" + p.replacesName() + "」换成「" + hotel.name() + "」";
            Itinerary updated = plan.itinerary();
            return tripService.saveVersion(ctx, tripId, new TripService.TripDraft(null, null, null, null, null,
                    updated.toMarkdown(), note, json(updated), false), "USER", detail.version().getVersion());
        });
    }

    private StayPlan planStay(TripService.TripDetail detail, Itinerary itinerary, String stayId, StayPick pick) {
        List<Itinerary.Stay> stays = itinerary.stays() == null ? List.of() : itinerary.stays();
        Itinerary.Stay old;
        if (stayId != null && !stayId.isBlank()) {
            old = stay(itinerary, stayId);
        } else if (stays.size() == 1) {
            old = stays.get(0);
        } else if (stays.size() > 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个行程有几段住宿，先选要换哪一段");
        } else {
            old = null;
        }
        if (old != null && itinerary.isBooked(old.id())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "「" + old.name() + "」已经标了已预约，先取消再换");
        }
        List<Itinerary.Day> days = itinerary.days();
        String checkIn = old != null ? old.checkIn() : days.get(0).date();
        String checkOut = old != null ? old.checkOut() : days.get(days.size() - 1).date();
        if (old == null && Objects.equals(checkIn, checkOut)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这是一日游，不用住宿");
        }
        String id = old != null ? old.id() : "s" + (stays.size() + 1);
        List<Itinerary.StayOption> options = new ArrayList<>();
        if (old != null) {
            options.add(new Itinerary.StayOption(old.name(), old.price(), old.platform(), old.url(), old.quotes()));
            (old.options() == null ? List.<Itinerary.StayOption>of() : old.options()).stream()
                    .filter(o -> !o.name().equals(pick.name()) && !o.name().equals(old.name())).forEach(options::add);
        }
        String city = detail.trip().getDestination();
        Itinerary.Stay fresh = new Itinerary.Stay(id, pick.name(), checkIn, checkOut, pick.address(), pick.price(),
                pick.platform(), pick.url(), pick.price() == null ? null : LocalDate.now().toString(), pick.quotes(),
                options.isEmpty() ? null : options, null, pick.lng(), pick.lat(), pick.photo(), pick.rating());
        fresh = geo.locate(fresh, city);
        Itinerary.Stay placed = fresh;
        List<Itinerary.Stay> newStays = new ArrayList<>(stays.stream().map(s -> old != null && s.id().equals(old.id()) ? placed : s).toList());
        if (old == null) {
            newStays.add(placed);
        }
        List<Itinerary.Day> newDays = new ArrayList<>();
        List<StayDay> affected = new ArrayList<>();
        for (int d = 0; d < days.size(); d++) {
            Itinerary.Day day = days.get(d);
            if (!placed.covers(day.date())) {
                newDays.add(day);
                continue;
            }
            // 关联到原酒店的项去掉坐标、改名，重新关联到新酒店；行程原来没有住宿时，酒店类的泛称会关联到新住宿
            List<Itinerary.Item> items = day.items().stream().map(i -> "HOTEL".equals(i.type()) && (old == null
                    ? !i.located() : linked(i, old)) ? new Itinerary.Item(i.id(), i.start(), i.end(), i.type(),
                    old == null ? i.name() : i.name().replace(old.name(), pick.name()), null, i.note(), i.cost(), null,
                    i.locked()) : i).toList();
            Itinerary.Day before = geo.relink(day, city, stays);
            Itinerary.Day after = geo.relink(new Itinerary.Day(day.date(), day.theme(), items), city, newStays);
            newDays.add(after);
            affected.add(new StayDay(d + 1, day.date(), DayTotals.of(before), DayTotals.of(after)));
        }
        List<String> notes = new ArrayList<>();
        int nights = placed.nights();
        if (pick.price() != null) {
            notes.add("每晚 ¥" + pick.price() + "（" + pick.platform() + "），" + nights + " 晚约 ¥" + pick.price() * nights
                    + "，税费和房型以平台页面为准");
        }
        if (!placed.located()) {
            notes.add("高德上没找到这家酒店的位置，路程没法重算");
        }
        if (old == null) {
            notes.add("行程原来没有登记住宿，会新加一段（" + checkIn + " 入住，" + checkOut + " 退房），"
                    + "\"回酒店\"这类安排会算上去酒店的路");
        }
        notes.add("这一步只改行程，不会下单；在平台订好后回来标记已预约");
        Itinerary updated = unbook(itinerary.withDays(newDays).withStays(newStays), id);
        return new StayPlan(new StayPreview(placed, old == null ? null : old.id(), old == null ? null : old.name(),
                old == null ? null : old.price(), nights, affected, notes), updated);
    }

    /** 这一项是不是关联在这家酒店上（同一个高德 id，或坐标一样）。 */
    private static boolean linked(Itinerary.Item i, Itinerary.Stay stay) {
        if (stay.poiId() != null && stay.poiId().equals(i.poiId())) {
            return true;
        }
        return stay.located() && i.located() && Math.abs(stay.lng() - i.lng()) < 1e-6 && Math.abs(stay.lat() - i.lat()) < 1e-6;
    }

    /** 检查页面传来的酒店：名称必填，照片只收高德图床，链接只收 http(s)。 */
    private static StayPick cleanStay(StayPick p) {
        if (p == null || p.name() == null || p.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没选酒店");
        }
        String url = p.url() == null || !p.url().matches("https?://\\S{1,600}") ? null : p.url();
        List<Itinerary.StayQuote> quotes = p.quotes() == null ? null : p.quotes().stream().limit(6)
                .map(q -> new Itinerary.StayQuote(cut(q.platform(), 10), q.price(),
                        q.url() != null && q.url().matches("https?://\\S{1,600}") ? q.url() : null, cut(q.basis(), 60),
                        cut(q.matchedName(), MAX_NAME))).toList();
        return new StayPick(cut(p.name().trim(), MAX_NAME), cut(p.address(), MAX_TEXT), p.lng(), p.lat(),
                safePhoto(p.photo()), p.rating(), p.price(), cut(p.platform(), 10), url, quotes);
    }

    /** 室内景点的名称特征：下雨天优先安排这些 */
    private static final java.util.regex.Pattern INDOOR = java.util.regex.Pattern.compile(
            "博物馆|博物院|美术馆|艺术馆|展览馆|展馆|科技馆|纪念馆|陈列馆|商场|购物中心|剧院|大剧院|影院|海洋馆|水族馆|图书馆|天文馆|体验馆|室内|演出|千古情");

    /** 景点是不是在室内（按名称判断，判断不了的都当户外）。 */
    public static boolean indoor(Itinerary.Item item) {
        return item.name() != null && INDOOR.matcher(item.name()).find();
    }

    /** 两天对调的预览：两天各自前后的路程对比，impacts 是会比原来晚到的安排，note 是一句说明。 */
    public record SwapPreview(DayPreview first, DayPreview second, List<Impact> impacts, String note) {
    }

    private record SwapPlan(SwapPreview preview, Itinerary itinerary) {
    }

    /** 预览把不同两天的两个景点对调（各自用对方原来的时间段），两天都按高德重算路线。不保存。 */
    public SwapPreview previewSwap(UserCtx ctx, long tripId, String aId, String bId) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return planSwap(detail, itinerary(detail), aId, bId).preview();
    }

    /** 确认对调：重新算一遍，两项的名称和预览时一致才保存成新版本，否则让用户重新预览。 */
    public TripService.TripDetail saveSwap(UserCtx ctx, long tripId, String aId, String bId, String aName, String bName) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            if (!Objects.equals(find(itinerary, aId).item().name(), aName)
                    || !Objects.equals(find(itinerary, bId).item().name(), bName)) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚有变化，请重新预览一次");
            }
            SwapPlan plan = planSwap(detail, itinerary, aId, bId);
            Itinerary updated = plan.itinerary();
            return tripService.saveVersion(ctx, tripId, new TripService.TripDraft(null, null, null, null, null,
                    updated.toMarkdown(), plan.preview().note(), json(updated), false), "USER",
                    detail.version().getVersion());
        });
    }

    private SwapPlan planSwap(TripService.TripDetail detail, Itinerary itinerary, String aId, String bId) {
        Found a = find(itinerary, aId);
        Found b = find(itinerary, bId);
        if (a.day() == b.day()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "两项在同一天，用调整顺序就行");
        }
        for (Found f : List.of(a, b)) {
            if (!"SPOT".equals(f.item().type())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "只能对调景点");
            }
            if (Boolean.TRUE.equals(f.item().locked()) || itinerary.isBooked(f.item().id())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "「" + f.item().name() + "」"
                        + (itinerary.isBooked(f.item().id()) ? "已预约" : "你设了保留") + "，不能挪");
            }
        }
        String city = detail.trip().getDestination();
        Itinerary.Item ai = a.item();
        Itinerary.Item bi = b.item();
        Itinerary.Item aMoved = movedTo(ai, bi);
        Itinerary.Item bMoved = movedTo(bi, ai);
        List<Itinerary.Day> days = new ArrayList<>(itinerary.days());
        List<Impact> impacts = new ArrayList<>();
        DayPreview[] previews = new DayPreview[2];
        int k = 0;
        for (Found f : List.of(a, b)) {
            Itinerary.Day day = itinerary.days().get(f.day());
            Itinerary.Item incoming = f == a ? bMoved : aMoved;
            Itinerary.Day before = geo.relink(day, city, itinerary.stays());
            Itinerary.Day after = geo.relink(new Itinerary.Day(day.date(), day.theme(),
                    items(itinerary, f.day(), f.item().id(), incoming)), city, itinerary.stays());
            Map<String, Integer> lateBefore = lateArrivals(before);
            lateArrivals(after).forEach((id, late) -> {
                if (late > lateBefore.getOrDefault(id, 0)) {
                    after.items().stream().filter(x -> x.id().equals(id)).findFirst().ifPresent(x -> impacts.add(
                            new Impact(id, x.name(), late, Boolean.TRUE.equals(x.locked()) || itinerary.isBooked(id))));
                }
            });
            days.set(f.day(), after);
            previews[k++] = new DayPreview(f.day() + 1, before, after, DayTotals.of(before), DayTotals.of(after), true);
        }
        String note = "第 " + (a.day() + 1) + " 天「" + ai.name() + "」和第 " + (b.day() + 1) + " 天「" + bi.name()
                + "」对调";
        return new SwapPlan(new SwapPreview(previews[0], previews[1], impacts, note), itinerary.withDays(days));
    }

    /** item 挪到 slot 那一项的位置：用 slot 的时间段，其余不变。 */
    private static Itinerary.Item movedTo(Itinerary.Item item, Itinerary.Item slot) {
        return new Itinerary.Item(item.id(), slot.start(), slot.end(), item.type(), item.name(), item.address(),
                item.note(), item.cost(), item.url(), item.locked(), item.poiId(), item.lng(), item.lat(), item.photo(),
                item.rating());
    }

    /**
     * 换一家酒店：name 是比价候选里的一家（带报价），也可以是用户自己说的酒店（只有名称，之后可重新比价）。
     * 原来那家放回候选里。关联到原酒店的入住、回酒店等项改关联新酒店，存新版本。
     */
    public TripService.TripDetail switchStay(UserCtx ctx, long tripId, String stayId, String name) {
        return locked(tripId, () -> {
            if (name == null || name.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "没选要换成的酒店");
            }
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            Itinerary.Stay old = stay(itinerary, stayId);
            List<Itinerary.StayOption> options = old.options() == null ? List.of() : old.options();
            Itinerary.StayOption picked = options.stream().filter(o -> o.name().equals(name)).findFirst()
                    .orElse(new Itinerary.StayOption(name.trim(), null, null, null, null));
            List<Itinerary.StayOption> rest = new ArrayList<>();
            rest.add(new Itinerary.StayOption(old.name(), old.price(), old.platform(), old.url(), old.quotes()));
            options.stream().filter(o -> o != picked && !o.name().equals(picked.name())).forEach(rest::add);
            Itinerary.Stay fresh = geo.locate(new Itinerary.Stay(old.id(), picked.name(), old.checkIn(), old.checkOut(), null,
                    picked.price(), picked.platform(), picked.url(), picked.price() == null ? null : old.queriedAt(),
                    picked.quotes(), rest, null, null, null, null, null), detail.trip().getDestination());
            List<Itinerary.Stay> stays = replaceStay(itinerary, fresh);
            // 关联到原酒店的项去掉坐标，重新关联到新酒店
            List<Itinerary.Day> days = new ArrayList<>();
            for (Itinerary.Day day : itinerary.days()) {
                if (!old.covers(day.date())) {
                    days.add(day);
                    continue;
                }
                List<Itinerary.Item> items = day.items().stream().map(i -> "HOTEL".equals(i.type()) && old.poiId() != null
                        && old.poiId().equals(i.poiId()) ? new Itinerary.Item(i.id(), i.start(), i.end(), i.type(),
                        i.name().replace(old.name(), picked.name()), null, i.note(), i.cost(), null, i.locked()) : i).toList();
                days.add(geo.relink(new Itinerary.Day(day.date(), day.theme(), items), detail.trip().getDestination(), stays));
            }
            Itinerary updated = unbook(itinerary.withDays(days).withStays(stays), stayId);
            TripService.TripDetail saved = tripService.saveVersion(ctx, tripId,
                    new TripService.TripDraft(null, null, null, null, null, updated.toMarkdown(),
                            "住宿从「" + old.name() + "」换成「" + picked.name() + "」", json(updated), false), "USER",
                        detail.version().getVersion());
            return saved;
        });
    }

    /** 重新比价：当前酒店和候选一起查各平台最新价格，只改当前版本的价格，不算新版本。查一次十几秒到一分钟。 */
    public TripService.TripDetail requote(UserCtx ctx, long tripId, String stayId) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary.Stay old = stay(itinerary(detail), stayId);
        LocalDate checkIn = parseDate(old.checkIn());
        LocalDate checkOut = parseDate(old.checkOut());
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这段住宿的入住、离店日期不对，请在对话里让助手改一下");
        }
        List<String> names = new ArrayList<>();
        names.add(old.name());
        if (old.options() != null) {
            old.options().forEach(o -> names.add(o.name()));
        }
        // 比价要十几秒到一分钟，在锁外面查，查完再在锁里读最新版本写回
        HotelQuoteBook.Comparison c = hotelPriceTools.quote(names, cityOf(detail.trip().getDestination()), checkIn, checkOut);
        Itinerary.StayOption best = TripTools.option(c.hotels().get(0));
        if (best.price() == null) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "这次各平台都没查到价格，原来的价格先保留，稍后再试");
        }
        List<Itinerary.StayOption> others = c.hotels().stream().skip(1).map(TripTools::option).toList();
        return locked(tripId, () -> {
            TripService.TripDetail latest = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(latest);
            Itinerary.Stay now = stay(itinerary, stayId);
            if (!now.name().equals(old.name())) {
                throw new BizException(ErrorCode.CONFLICT, "比价期间酒店被换掉了，刷新后再试");
            }
            Itinerary.Stay fresh = new Itinerary.Stay(now.id(), now.name(), now.checkIn(), now.checkOut(), now.address(),
                    best.price(), best.platform(), best.url(), c.queriedAt(), best.quotes(), others, now.poiId(),
                    now.lng(), now.lat(), now.photo(), now.rating());
            Itinerary updated = itinerary.withStays(replaceStay(itinerary, fresh));
            String json = json(updated);
            String content = updated.toMarkdown();
            tripService.updateItinerary(tripId, latest.version().getVersion(), json, content);
            latest.version().setItinerary(json);
            latest.version().setContent(content);
            return latest;
        });
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }


    private static Itinerary.Stay stay(Itinerary itinerary, String stayId) {
        return (itinerary.stays() == null ? List.<Itinerary.Stay>of() : itinerary.stays()).stream()
                .filter(s -> s.id().equals(stayId)).findFirst().orElseThrow(() -> BizException.notFound("这段住宿"));
    }

    private static List<Itinerary.Stay> replaceStay(Itinerary itinerary, Itinerary.Stay fresh) {
        return itinerary.stays().stream().map(s -> s.id().equals(fresh.id()) ? fresh : s).toList();
    }

    /** 目的地取城市部分："深圳·梧桐山蝴蝶谷" → "深圳"。 */
    private static String cityOf(String destination) {
        return destination == null ? "" : destination.split("[·・\\s,，]")[0];
    }

    /** 给新加的项挑时间：饭点优先，否则接在最后一项后面一小时。 */
    static String[] slot(List<Itinerary.Item> items, String type) {
        if ("FOOD".equals(type)) {
            // 午饭在 11:00–14:00、晚饭在 17:00–20:30 里找一段没被占的时间，已经有饭的那顿跳过
            for (LocalTime[] meal : List.of(new LocalTime[]{LocalTime.of(11, 0), LocalTime.of(14, 0)},
                    new LocalTime[]{LocalTime.of(17, 0), LocalTime.of(20, 30)})) {
                boolean hasMeal = items.stream().anyMatch(i -> "FOOD".equals(i.type()) && i.start() != null
                        && !time(i.start()).isBefore(meal[0]) && time(i.start()).isBefore(meal[1]));
                LocalTime[] gap = hasMeal ? null : gap(items, meal[0], meal[1], 45);
                if (gap != null) {
                    LocalTime end = gap[1].isAfter(gap[0].plusMinutes(60)) ? gap[0].plusMinutes(60) : gap[1];
                    return new String[]{gap[0].toString(), end.toString()};
                }
            }
        }
        LocalTime last = items.stream().map(i -> i.end() != null ? i.end() : i.start()).filter(Objects::nonNull)
                .map(TripEditor::time).max(Comparator.naturalOrder()).orElse(LocalTime.of(9, 0));
        LocalTime end = last.plusHours(1).isBefore(last) ? LocalTime.of(23, 59) : last.plusHours(1);
        return new String[]{last.toString(), end.toString()};
    }

    /** 在 from–to 之间找第一段至少 minutes 分钟、不和任何安排重叠的空档；没有返回空。 */
    private static LocalTime[] gap(List<Itinerary.Item> items, LocalTime from, LocalTime to, int minutes) {
        List<LocalTime[]> busy = items.stream().filter(i -> i.start() != null)
                .map(i -> new LocalTime[]{time(i.start()), i.end() == null ? time(i.start()).plusMinutes(30) : time(i.end())})
                .sorted(Comparator.comparing((LocalTime[] b) -> b[0])).toList();
        LocalTime cursor = from;
        for (LocalTime[] b : busy) {
            if (!b[1].isAfter(cursor)) {
                continue;
            }
            if (b[0].isAfter(cursor) && !cursor.plusMinutes(minutes).isAfter(b[0]) && !cursor.plusMinutes(minutes).isAfter(to)) {
                return new LocalTime[]{cursor, b[0].isBefore(to) ? b[0] : to};
            }
            if (b[1].isAfter(cursor)) {
                cursor = b[1];
            }
            if (!cursor.isBefore(to)) {
                return null;
            }
        }
        return !cursor.plusMinutes(minutes).isAfter(to) ? new LocalTime[]{cursor, to} : null;
    }

    private static LocalTime time(String hhmm) {
        return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
    }

    /** 用新的一天替换原来那天，补坐标、重算路程，生成正文，存新版本，再后台补其余没坐标的项。 */
    private TripService.TripDetail save(UserCtx ctx, TripService.TripDetail detail, Itinerary itinerary, int dayIndex,
                                        List<Itinerary.Item> items, String note) {
        Itinerary.Day day = itinerary.days().get(dayIndex);
        Itinerary.Day relinked = geo.relink(new Itinerary.Day(day.date(), day.theme(), items), detail.trip().getDestination(),
                itinerary.stays());
        Itinerary updated = withDay(itinerary, dayIndex, relinked);
        Trip trip = detail.trip();
        TripService.TripDetail saved = tripService.saveVersion(ctx, trip.getId(),
                new TripService.TripDraft(null, null, null, null, null, updated.toMarkdown(), note, json(updated), false),
                "USER", detail.version().getVersion());
        if (!Boolean.TRUE.equals(updated.geo())) {
            ensureGeoLater(trip.getId(), saved.version().getVersion());
        }
        return saved;
    }

    private TripService.TripDetail enrich(long tripId, int version, TripService.TripDetail known) {
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.getInternal(tripId, version);
            if (detail == null) {
                return known;
            }
            Itinerary itinerary = parse(detail.version().getItinerary());
            if (itinerary == null || Boolean.TRUE.equals(itinerary.geo()) && !missingRoutes(itinerary)
                    && !TripGeo.missingPlaces(itinerary)) {
                return detail;
            }
            // 还没坐标的地点每次打开都再找一次（高德查询有缓存）；结果没变就不写库
            String json = json(geo.enrich(itinerary, detail.trip().getDestination()));
            if (json.equals(json(itinerary))) {
                return detail;
            }
            tripService.updateItinerary(tripId, version, json);
            detail.version().setItinerary(json);
            return detail;
        });
    }

    /** 早先算的驾车路程没存打车费、远的路段还没查公交，补一次。 */
    static boolean missingRoutes(Itinerary itinerary) {
        return itinerary.days().stream().filter(d -> d.legs() != null).flatMap(d -> d.legs().stream())
                .anyMatch(l -> !Itinerary.WALK.equals(l.mode()) && (l.transit() == null
                        || Itinerary.DRIVE.equals(l.mode()) && l.taxi() == null));
    }

    /** 在这个行程的锁里执行；锁是可重入的，里面再调别的加锁方法没问题。用 ReentrantLock 而不是 synchronized，免得卡住虚拟线程。 */
    private <T> T locked(long tripId, Supplier<T> body) {
        ReentrantLock lock = stripes[Math.floorMod(Long.hashCode(tripId), LOCK_STRIPES)];
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清理页面传来的地点：名称必填、长度有上限，类型只认那几种，照片只收高德图床的 https 地址
     * （导出 PDF 时服务器会去下载照片，不能让人借此访问任意地址）。
     */
    static PlacePick clean(PlacePick pick) {
        if (pick == null || pick.name() == null || pick.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没选地点");
        }
        String type = pick.type() == null ? null : pick.type().trim().toUpperCase();
        return new PlacePick(cut(pick.name().trim(), MAX_NAME), cut(pick.poiId(), 40), cut(pick.address(), MAX_TEXT),
                pick.lng(), pick.lat(), safePhoto(pick.photo()), pick.rating(), pick.cost(),
                type == null || !Itinerary.TYPES.contains(type) ? null : type, cut(pick.note(), MAX_TEXT),
                pick.price(), pick.platform(), pick.url(), pick.quotes());
    }

    /** 只收高德图床（*.autonavi.com、*.amap.com）的 https 照片地址，其余当没有照片。 */
    public static String safePhoto(String url) {
        if (url == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.endsWith(".autonavi.com") || host.endsWith(".amap.com")) ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    /** 去过的、不感兴趣的记进偏好记忆，规划时会读到。 */
    private void remember(UserCtx ctx, String name, Reason reason) {
        if (reason == Reason.VISITED) {
            memory.upsert(ctx, MemoryCategory.PREFERENCE, VISITED + name, "去过" + name + "，不用再推荐",
                    MemorySource.USER_STATED, null);
        } else if (reason == Reason.DISLIKE) {
            memory.upsert(ctx, MemoryCategory.PREFERENCE, DISLIKED + name, "对" + name + "不感兴趣，不要推荐",
                    MemorySource.USER_STATED, null);
        }
    }

    /** 记忆里去过、不感兴趣的地名。 */
    List<String> avoided(UserCtx ctx) {
        return memory.listForOwner(ctx).stream().map(MemoryItem::getMemKey)
                .filter(k -> k != null && (k.startsWith(VISITED) || k.startsWith(DISLIKED)))
                .map(k -> k.substring(k.indexOf(':') + 1))
                .toList();
    }

    private record Found(int day, Itinerary.Item item) {
    }

    private static Found find(Itinerary itinerary, String itemId) {
        for (int d = 0; d < itinerary.days().size(); d++) {
            for (Itinerary.Item item : itinerary.days().get(d).items()) {
                if (item.id().equals(itemId)) {
                    return new Found(d, item);
                }
            }
        }
        throw BizException.notFound("这项安排");
    }

    /** 某天的安排，把 id 那项换成 replacement（为空就删掉）。 */
    private static List<Itinerary.Item> items(Itinerary itinerary, int day, String id, Itinerary.Item replacement) {
        List<Itinerary.Item> out = new ArrayList<>();
        for (Itinerary.Item item : itinerary.days().get(day).items()) {
            if (!item.id().equals(id)) {
                out.add(item);
            } else if (replacement != null) {
                out.add(replacement);
            }
        }
        return out;
    }

    private static Itinerary withDay(Itinerary itinerary, int index, Itinerary.Day day) {
        List<Itinerary.Day> days = new ArrayList<>(itinerary.days());
        days.set(index, day);
        return itinerary.withDays(days);
    }

    Itinerary itinerary(TripService.TripDetail detail) {
        Itinerary itinerary = parse(detail.version().getItinerary());
        if (itinerary == null || itinerary.days() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个行程是早期保存的，没有逐项安排，请在对话里让助手修改");
        }
        return itinerary;
    }

    private Itinerary parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Itinerary.class);
        } catch (JsonProcessingException e) {
            log.warn("行程 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private String json(Itinerary itinerary) {
        try {
            return objectMapper.writeValueAsString(itinerary.resolveLegs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
