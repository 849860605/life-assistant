package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import com.lifeassistant.core.memory.MemoryCategory;
import com.lifeassistant.core.memory.MemoryItem;
import com.lifeassistant.core.memory.MemoryService;
import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 行程里的美食推荐：按某天某一顿的前后安排，在顺路的地方找餐厅（高德），可以用一句话让助手找。
 * 助手（模型）只负责把话拆成筛选条件，店名、人均、评分、距离、路上时间都来自高德，不让模型编。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripFood {

    /** 店名、分类、标签里有这些字的当成辣口，"不吃辣"时排除（只能粗筛，不保证菜品不辣） */
    private static final Pattern SPICY = Pattern.compile("辣|川菜|湘菜|火锅|串串|冒菜|重庆|烤鱼|酸菜鱼|小龙虾|麻辣|钵钵|贵州|蜀");
    /** "本地特色"额外搜的关键字 */
    private static final List<String> LOCAL_WORDS = List.of("老字号", "特色小吃");
    private static final int MAX_RESULTS = 20;
    private static final int DEFAULT_RADIUS = 2000;
    private static final int MAX_RADIUS = 10000;
    /** 助手回答时，前几家按高德查实际路上时间 */
    private static final int ROUTED = 3;

    private final TripService tripService;
    private final TripEditor editor;
    private final AmapPlaceService places;
    private final MemoryService memory;
    private final ChatModelRegistry chatModels;
    private final LifeProperties properties;
    private final UsageRecorder usageRecorder;

    /**
     * 筛选条件。near 是"在哪附近"（行程里某项或任意地名，空表示按这一顿前后的安排），keyword 是菜系或品类，
     * maxCost 是人均上限（元），radius 是搜索半径（米），noSpicy 不吃辣，local 优先本地特色。
     */
    public record FoodQuery(String near, TripEditor.Meal meal, String keyword, Integer maxCost, Integer radius,
                            boolean noSpicy, boolean local) {
    }

    /**
     * 一家候选：detourKm 是绕路多少公里（前后两项之间改道去这家多走的直线距离，只有一头时是离那一头的直线距离），
     * minutes、mode 是从上一项过去的路上时间（高德，只有助手回答的前几家有），local 是按本地特色关键字搜到的，
     * reason 是按这些数据拼的推荐理由。
     */
    public record Candidate(AmapPlaceService.Place place, Double detourKm, Integer minutes, String mode, boolean local,
                            String reason) {
    }

    /**
     * 查询结果：第几天、哪一顿、按哪里找的（anchor 名称和坐标）、这一顿前后挨着的安排、这一顿现在安排的餐厅，
     * 实际用的筛选条件，候选列表。summary 是助手回答时的一句话说明。
     */
    public record FoodResult(int day, TripEditor.Meal meal, String anchor, Double anchorLng, Double anchorLat,
                             String prevName, String nextName, String currentName, FoodQuery query,
                             List<Candidate> places, String summary) {
    }

    /** 模型从一句话里拆出来的条件，都可以为空。 */
    public record FoodIntent(String near, String meal, String keyword, Integer maxCost, Boolean noSpicy, Boolean local,
                             Integer radius) {
    }

    /** 按筛选条件找第 dayIndex 天（从 0 开始）某一顿的餐厅。 */
    public FoodResult find(UserCtx ctx, long tripId, int dayIndex, FoodQuery query) {
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        return find(ctx, detail, editor.itinerary(detail), dayIndex, query, false, null);
    }

    /**
     * 一句话找餐厅（如"我不吃辣，想在兵马俑附近吃午饭，人均 50 元以内，尽量不绕路"）：模型拆成条件，再按条件在高德找，
     * 前几家查实际路上时间。没说的条件沿用页面上当前选的 meal。
     */
    public FoodResult ask(UserCtx ctx, long tripId, int dayIndex, TripEditor.Meal meal, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "想吃什么？说一句就行");
        }
        if (text.length() > 300) {
            throw new BizException(ErrorCode.BAD_REQUEST, "说得简短一点，300 字以内");
        }
        TripService.TripDetail detail = tripService.get(ctx, tripId, null);
        Itinerary itinerary = editor.itinerary(detail);
        Itinerary.Day day = day(itinerary, dayIndex);
        FoodIntent intent = understand(ctx, detail.trip().getDestination(), day, meal, text.trim());
        TripEditor.Meal asked = meal;
        if (intent.meal() != null) {
            try {
                asked = TripEditor.Meal.valueOf(intent.meal().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 模型给了不认识的值，沿用页面上的
            }
        }
        FoodQuery query = new FoodQuery(intent.near(), asked, intent.keyword(), intent.maxCost(),
                intent.radius(), Boolean.TRUE.equals(intent.noSpicy()), Boolean.TRUE.equals(intent.local()));
        return find(ctx, detail, itinerary, dayIndex, query, true, text.trim());
    }

    private FoodResult find(UserCtx ctx, TripService.TripDetail detail, Itinerary itinerary, int dayIndex,
                            FoodQuery query, boolean route, String asked) {
        if (!places.available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "还没有配置高德 Key，找不了餐厅");
        }
        Itinerary.Day day = day(itinerary, dayIndex);
        TripEditor.Meal meal = query.meal() == null ? TripEditor.Meal.LUNCH : query.meal();
        String city = detail.trip().getDestination();
        Neighbors around = neighbors(day, itinerary.stays(), meal);
        // 找的中心：用户说了在哪附近就用那里（先找行程里的项，再在城市里搜），否则用这一顿前一项、后一项
        double[] anchor = null;
        String anchorName = null;
        if (query.near() != null && !query.near().isBlank()) {
            String near = query.near().trim();
            Itinerary.Item hit = day.items().stream().filter(Itinerary.Item::located)
                    .filter(i -> AmapPlaceService.similarName(near, i.name()) || i.name().contains(near)).findFirst()
                    .orElse(null);
            if (hit != null) {
                anchor = new double[]{hit.lng(), hit.lat()};
                anchorName = hit.name();
            } else {
                AmapPlaceService.Place p = places.search(near, cityOf(city), null, 3).stream()
                        .filter(x -> x.lng() != null).findFirst().orElse(null);
                if (p != null) {
                    anchor = new double[]{p.lng(), p.lat()};
                    anchorName = p.name();
                }
            }
        }
        if (anchor == null && around.prev() != null) {
            anchor = point(around.prev());
            anchorName = around.prev().name();
        }
        if (anchor == null && around.next() != null) {
            anchor = point(around.next());
            anchorName = around.next().name();
        }
        if (anchor == null) {
            anchor = places.center(cityOf(city)).orElseThrow(() ->
                    new BizException(ErrorCode.BAD_REQUEST, "这一天的安排还没有坐标，打开路线地图页补一下再找"));
            anchorName = cityOf(city) + "市中心";
        }
        int radius = query.radius() == null ? DEFAULT_RADIUS : Math.max(300, Math.min(query.radius(), MAX_RADIUS));
        List<String> words = new ArrayList<>();
        if (query.keyword() != null && !query.keyword().isBlank()) {
            words.add(query.keyword().trim());
        }
        if (query.local()) {
            words.addAll(LOCAL_WORDS);
        }
        if (words.isEmpty()) {
            words.add("美食");
        }
        Map<String, AmapPlaceService.Place> merged = new LinkedHashMap<>();
        Set<String> localIds = new java.util.HashSet<>();
        for (String word : words.stream().distinct().limit(4).toList()) {
            for (AmapPlaceService.Place p : places.around(anchor[0], anchor[1], word, AmapPlaceService.FOOD_TYPES, radius, 25)) {
                String key = p.id() == null ? p.name() : p.id();
                merged.putIfAbsent(key, p);
                if (LOCAL_WORDS.contains(word)) {
                    localIds.add(key);
                }
            }
        }
        Itinerary.Item current = day.items().stream().filter(meal::holds).findFirst().orElse(null);
        List<String> avoid = new ArrayList<>(editor.avoided(ctx));
        // 别的饭点已经安排的不再推荐；这一顿现在的那家留着，方便对比
        itinerary.days().stream().flatMap(d -> d.items().stream()).filter(i -> "FOOD".equals(i.type()) && i != current)
                .map(Itinerary.Item::name).forEach(avoid::add);
        List<Candidate> list = merged.entrySet().stream()
                .filter(e -> e.getValue().lng() != null && e.getValue().lat() != null)
                .filter(e -> avoid.stream().noneMatch(n -> AmapPlaceService.similarName(n, e.getValue().name())))
                .filter(e -> query.maxCost() == null || e.getValue().cost() == null || e.getValue().cost() <= query.maxCost())
                .filter(e -> !query.noSpicy() || !spicy(e.getValue()))
                .map(e -> new Candidate(e.getValue(), detour(around, e.getValue()), null, null, localIds.contains(e.getKey()), null))
                .sorted(Comparator.comparingDouble(TripFood::score).reversed())
                .limit(MAX_RESULTS)
                .toList();
        List<Candidate> out = new ArrayList<>();
        for (int k = 0; k < list.size(); k++) {
            Candidate c = list.get(k);
            Integer minutes = null;
            String mode = null;
            Itinerary.Item from = around.prev() != null ? around.prev() : around.next();
            if (route && k < ROUTED && from != null) {
                double straight = TripGeo.distanceKm(from.lat(), from.lng(), c.place().lat(), c.place().lng());
                if (straight <= 1.2) {
                    minutes = (int) Math.max(1, Math.round(straight * 1.3 / 4.5 * 60));
                    mode = "WALK";
                } else {
                    var r = places.driving(point(from), new double[]{c.place().lng(), c.place().lat()}).orElse(null);
                    if (r != null) {
                        minutes = r.minutes();
                        mode = "DRIVE";
                    }
                }
            }
            out.add(new Candidate(c.place(), c.detourKm(), minutes, mode, c.local(),
                    reason(c, anchorName, from == null ? null : from.name(), minutes, mode, query)));
        }
        String summary = asked == null ? null : summary(query, meal, anchorName, out.size());
        return new FoodResult(dayIndex + 1, meal, anchorName, anchor[0], anchor[1],
                around.prev() == null ? null : around.prev().name(), around.next() == null ? null : around.next().name(),
                current == null ? null : current.name(), new FoodQuery(query.near(), meal, query.keyword(), query.maxCost(),
                radius, query.noSpicy(), query.local()), out, summary);
    }

    /** 这一顿前后挨着的、有坐标的安排（早餐前面没有就用住处）。 */
    record Neighbors(Itinerary.Item prev, Itinerary.Item next) {
    }

    static Neighbors neighbors(Itinerary.Day day, List<Itinerary.Stay> stays, TripEditor.Meal meal) {
        Itinerary.Item current = day.items().stream().filter(meal::holds).findFirst().orElse(null);
        LocalTime at = current != null ? time(current.start()) : meal.from.plusMinutes(60);
        Itinerary.Item prev = null;
        Itinerary.Item next = null;
        for (Itinerary.Item i : day.items()) {
            if (i == current || !i.located() || i.start() == null) {
                continue;
            }
            if (time(i.start()).isBefore(at)) {
                prev = i;
            } else if (next == null) {
                next = i;
            }
        }
        if (prev == null && meal == TripEditor.Meal.BREAKFAST) {
            Itinerary.Stay stay = TripGeo.stayFor(stays, day.date(), "08:00");
            if (stay != null && stay.located()) {
                prev = new Itinerary.Item("@stay", null, null, "HOTEL", stay.name(), null, null, null, null, null,
                        stay.poiId(), stay.lng(), stay.lat(), null, null);
            }
        }
        return new Neighbors(prev, next);
    }

    /** 绕路公里数（直线）：前后都有时是"上一项→这家→下一项"比"上一项→下一项"多出来的，只有一头时是离那一头的距离。 */
    static Double detour(Neighbors n, AmapPlaceService.Place p) {
        if (n.prev() == null && n.next() == null) {
            return null;
        }
        if (n.prev() == null || n.next() == null) {
            Itinerary.Item one = n.prev() != null ? n.prev() : n.next();
            return round1(TripGeo.distanceKm(one.lat(), one.lng(), p.lat(), p.lng()));
        }
        double via = TripGeo.distanceKm(n.prev().lat(), n.prev().lng(), p.lat(), p.lng())
                + TripGeo.distanceKm(p.lat(), p.lng(), n.next().lat(), n.next().lng());
        double direct = TripGeo.distanceKm(n.prev().lat(), n.prev().lng(), n.next().lat(), n.next().lng());
        return round1(Math.max(0, via - direct));
    }

    /** 排序分：高德评分为主（没评分按 3.5），每绕路 1 公里扣 0.4 分，本地特色加 0.3 分。 */
    private static double score(Candidate c) {
        double rating = c.place().rating() == null ? 3.5 : c.place().rating();
        return rating - (c.detourKm() == null ? 0 : c.detourKm() * 0.4) + (c.local() ? 0.3 : 0);
    }

    static boolean spicy(AmapPlaceService.Place p) {
        String text = p.name() + " " + (p.category() == null ? "" : p.category()) + " "
                + (p.tags() == null ? "" : String.join(" ", p.tags()));
        return SPICY.matcher(text).find();
    }

    /** 推荐理由：只用高德的数据和算出来的距离拼，不让模型写。 */
    private static String reason(Candidate c, String anchor, String from, Integer minutes, String mode, FoodQuery q) {
        List<String> parts = new ArrayList<>();
        AmapPlaceService.Place p = c.place();
        if (p.cost() != null) {
            parts.add("人均 ¥" + p.cost() + (q.maxCost() != null ? "（预算 ¥" + q.maxCost() + " 内）" : ""));
        } else {
            parts.add("高德没有人均");
        }
        if (p.distance() != null) {
            parts.add("离" + anchor + (p.distance() < 1000 ? " " + p.distance() + " 米" : " " + round1(p.distance() / 1000.0) + " 公里"));
        }
        if (c.detourKm() != null) {
            parts.add(c.detourKm() < 0.3 ? "基本顺路" : "绕路约 " + c.detourKm() + " 公里（直线）");
        }
        if (minutes != null && from != null) {
            parts.add("从" + from + ("WALK".equals(mode) ? "步行约 " : "打车约 ") + minutes + " 分钟");
        }
        if (p.rating() != null) {
            parts.add("高德评分 " + p.rating());
        }
        if (c.local()) {
            parts.add("按\"老字号/特色小吃\"搜到");
        }
        return String.join(" · ", parts);
    }

    private static String summary(FoodQuery q, TripEditor.Meal meal, String anchor, int count) {
        List<String> parts = new ArrayList<>();
        parts.add("在" + anchor + "附近找" + meal.label);
        if (q.keyword() != null && !q.keyword().isBlank()) {
            parts.add("品类：" + q.keyword());
        }
        if (q.maxCost() != null) {
            parts.add("人均 ¥" + q.maxCost() + " 以内");
        }
        if (q.noSpicy()) {
            parts.add("排除辣口（按店名和标签粗筛）");
        }
        if (q.local()) {
            parts.add("优先本地特色");
        }
        return String.join("，", parts) + (count == 0 ? "。没找到合适的，放宽一下条件试试。"
                : "。按评分和顺路程度排了 " + count + " 家，前 " + Math.min(ROUTED, count) + " 家查了实际路上时间。");
    }

    /** 让模型把一句话拆成筛选条件；行程这天的安排、偏好记忆一起给它，方便理解"兵马俑附近""我不吃辣"。 */
    private FoodIntent understand(UserCtx ctx, String city, Itinerary.Day day, TripEditor.Meal meal, String text) {
        String items = day.items().stream().map(i -> (i.start() == null ? "" : i.start() + " ") + i.name())
                .collect(Collectors.joining("；"));
        String prefs = memory.listForOwner(ctx).stream()
                .filter(m -> MemoryCategory.PREFERENCE.name().equals(m.getCategory()) && !Boolean.TRUE.equals(m.getSensitive()))
                .map(MemoryItem::getContent).filter(c -> c != null && c.length() < 80).limit(30)
                .collect(Collectors.joining("；"));
        String system = """
                你帮用户在旅行中找餐厅。把用户这句话拆成筛选条件，只输出 JSON，没提到的字段填 null：
                near：在哪附近（行程里的地点名或其他地名，没说填 null）；
                meal：BREAKFAST、LUNCH、DINNER 之一（没说填 null）；
                keyword：菜系或品类，一个词，比如"面馆""泡馍""火锅""清真""咖啡"（没说填 null）；
                maxCost：人均上限，整数元；
                noSpicy：不吃辣、要清淡时为 true（偏好记忆里写了不吃辣也算）；
                local：想吃当地特色、老字号时为 true；
                radius：搜索半径米数，"尽量不绕路""就近"填 1000，"远一点也行"填 5000，没说填 null。
                不要推荐具体店名，不要编造价格和距离。
                城市：%s。这天（%s）的安排：%s。页面上当前选的是%s。
                用户的偏好记忆：%s""".formatted(city, day.date(), items, meal.label, prefs.isEmpty() ? "无" : prefs);
        try {
            ModelSpec model = chatModels.resolve(properties.getModels().getRouter());
            ResponseEntity<ChatResponse, FoodIntent> response = chatModels.client(model).prompt()
                    .system(system)
                    .user(text)
                    .options(ChatModelRegistry.options(model, 0.0))
                    .call()
                    .responseEntity(FoodIntent.class);
            if (response.response() != null) {
                usageRecorder.record(ctx.userId(), null, "FOOD_ASK", model.id(), response.response().getMetadata().getUsage());
            }
            if (response.entity() == null) {
                throw new BizException(ErrorCode.EXTERNAL_SERVICE, "助手没听懂，换个说法，或者用上面的筛选");
            }
            return response.entity();
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("美食助手解析失败：{}", e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "助手暂时不可用，先用上面的筛选找");
        }
    }

    private static Itinerary.Day day(Itinerary itinerary, int dayIndex) {
        if (dayIndex < 0 || dayIndex >= itinerary.days().size()) {
            throw BizException.notFound("这一天");
        }
        return itinerary.days().get(dayIndex);
    }

    private static double[] point(Itinerary.Item i) {
        return new double[]{i.lng(), i.lat()};
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static LocalTime time(String hhmm) {
        return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
    }

    /** 目的地取城市部分："西安·兵马俑" → "西安"。 */
    private static String cityOf(String destination) {
        return destination == null ? "" : destination.split("[·・\\s,，]")[0];
    }
}
