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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

    /** 用户选中的地点（来自候选列表或搜索结果）。type 只在加入行程时用。 */
    public record PlacePick(String name, String poiId, String address, Double lng, Double lat, String photo,
                            Double rating, Integer cost, String type, String note) {
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

    /** 某项的候选地点：在它附近找同类、评分高的，排除行程里已有的和用户去过、不感兴趣的。 */
    public List<AmapPlaceService.Place> alternatives(UserCtx ctx, long tripId, String itemId, String keyword) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = itinerary(detail);
        Itinerary.Item item = find(itinerary, itemId).item();
        String types = switch (item.type()) {
            case "FOOD" -> AmapPlaceService.FOOD_TYPES;
            case "HOTEL" -> AmapPlaceService.HOTEL_TYPES;
            default -> AmapPlaceService.SPOT_TYPES + "|140100";
        };
        if (keyword != null && !keyword.isBlank() && !"FOOD".equals(item.type()) && !"HOTEL".equals(item.type())) {
            // 高德同时给关键字和景点分类时会忽略关键字
            types = null;
        }
        String city = detail.trip().getDestination();
        List<AmapPlaceService.Place> found = item.located()
                ? places.around(item.lng(), item.lat(), keyword, types, AROUND_RADIUS, 25)
                : places.search(keyword == null || keyword.isBlank() ? "景点" : keyword, city, types, 25);
        Set<String> avoid = itinerary.days().stream().flatMap(d -> d.items().stream()).map(Itinerary.Item::name)
                .collect(Collectors.toSet());
        avoid.addAll(avoided(ctx));
        return found.stream()
                .filter(p -> avoid.stream().noneMatch(n -> AmapPlaceService.similarName(n, p.name())))
                .sorted(Comparator.comparing(AmapPlaceService.Place::rating, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_ALTERNATIVES)
                .toList();
    }

    /** 把某项换成选中的地点，时间不变，重算当天路程。 */
    public TripService.TripDetail replace(UserCtx ctx, long tripId, String itemId, PlacePick pick, Reason reason) {
        PlacePick place = clean(pick);
        return locked(tripId, () -> {
            TripService.TripDetail detail = tripService.get(ctx, tripId, null);
            Itinerary itinerary = itinerary(detail);
            Found found = find(itinerary, itemId);
            Itinerary.Item old = found.item();
            Itinerary.Item fresh = new Itinerary.Item(old.id(), old.start(), old.end(), old.type(), place.name(),
                    place.address(), place.note(), place.cost(), null, null, place.poiId(), place.lng(), place.lat(), place.photo(),
                    place.rating());
            remember(ctx, old.name(), reason);
            return save(ctx, detail, unbook(itinerary, old.id()), found.day(), items(itinerary, found.day(), old.id(), fresh),
                    "把「" + old.name() + "」换成「" + place.name() + "」");
        });
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

    /** 换掉、删掉的项不再算已预约。 */
    private static Itinerary unbook(Itinerary itinerary, String id) {
        if (!itinerary.isBooked(id)) {
            return itinerary;
        }
        List<String> ids = new ArrayList<>(itinerary.booked());
        ids.remove(id);
        return itinerary.withBooked(ids);
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
            if (itinerary == null || Boolean.TRUE.equals(itinerary.geo())) {
                return detail;
            }
            String json = json(geo.enrich(itinerary, detail.trip().getDestination()));
            tripService.updateItinerary(tripId, version, json);
            detail.version().setItinerary(json);
            return detail;
        });
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
                type == null || !Itinerary.TYPES.contains(type) ? null : type, cut(pick.note(), MAX_TEXT));
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
    private List<String> avoided(UserCtx ctx) {
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

    private Itinerary itinerary(TripService.TripDetail detail) {
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
            return objectMapper.writeValueAsString(itinerary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
