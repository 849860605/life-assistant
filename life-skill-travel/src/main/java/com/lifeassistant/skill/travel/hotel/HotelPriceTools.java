package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.mcp.McpServerRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 多平台比价：同时向携程、美团、飞猪等已配置的平台查价。
 * findAndCompareHotels 在一个工具里完成"RollingGo 选候选 + 各平台并行查价"，比模型分两轮调用少一次往返。
 */
@Component
public class HotelPriceTools {

    private static final int MAX_HOTELS = 5;
    private static final int DEFAULT_CANDIDATES = 4;
    /** 整体最多等这么久，慢的平台超时记为失败 */
    private static final long DEADLINE_SECONDS = 60;

    private final List<HotelPriceSource> sources;
    private final McpServerRegistry mcpServers;
    private final ObjectMapper objectMapper;
    private final CallRecorder recorder;
    private final CardHub cards;

    public HotelPriceTools(List<HotelPriceSource> sources, McpServerRegistry mcpServers, ObjectMapper objectMapper,
                           CallRecorder recorder, CardHub cards) {
        this.sources = sources;
        this.mcpServers = mcpServers;
        this.objectMapper = objectMapper;
        this.recorder = recorder;
        this.cards = cards;
    }

    /** 按地点找候选酒店并一次完成多平台比价；RollingGo 没配置时提示改用地图找酒店再 compareHotelPrices。 */
    @Tool(description = """
            按地点找酒店并一次完成多平台比价（RollingGo、飞猪、携程、美团）。用户要"某地附近的酒店比价"时优先用这个，
            一次调用就够，不用先 searchHotels 再 compareHotelPrices。返回每家酒店各平台的每晚价格、口径和预订链接。
            查一次要十几秒，调用前先告诉用户正在比价。""")
    public Map<String, Object> findAndCompareHotels(
            @ToolParam(description = "地点：酒店名、地铁站、景点、区县等，位置或区县后面空格加城市，如：后海地铁站 深圳") String place,
            @ToolParam(description = "地点类型：城市、机场、景点、火车站、地铁站、酒店、区/县、详细地址") String placeType,
            @ToolParam(description = "城市，例如：深圳") String city,
            @ToolParam(description = "入住日期 yyyy-MM-dd") String checkIn,
            @ToolParam(description = "离店日期 yyyy-MM-dd") String checkOut,
            @ToolParam(description = "每晚预算上限（元），没有就不填", required = false) Double maxPrice,
            @ToolParam(description = "最低星级，如 4；没有要求就不填", required = false) Double minStar,
            @ToolParam(description = "候选酒店数量，默认 4，最多 5", required = false) Integer count) {
        LocalDate in = date(checkIn);
        LocalDate out = date(checkOut);
        Optional<ToolCallback> search = mcpServers.tool("rollinggo", "searchHotels");
        if (search.isEmpty()) {
            return Map.of("error", "RollingGo 没有配置，改用地图工具找酒店，再用 compareHotelPrices 比价");
        }
        int size = Math.min(count == null ? DEFAULT_CANDIDATES : count, MAX_HOTELS);
        List<HotelPriceSource.Quote> rollingGo;
        List<String> names;
        try {
            String args = searchArgs(place, placeType, in, out, maxPrice, minStar, size);
            JsonNode list = unwrap(recorder.platform("RollingGo", args, () -> search.get().call(args)))
                    .path("hotelInformationList");
            names = new ArrayList<>();
            rollingGo = new ArrayList<>();
            for (JsonNode hotel : list) {
                if (names.size() >= size) {
                    break;
                }
                String name = hotel.path("name").asText();
                names.add(name);
                JsonNode price = hotel.path("price");
                rollingGo.add(HotelPriceSource.Quote.ok("RollingGo",
                        price.path("hasPrice").asBoolean() ? (int) Math.round(price.path("lowestPrice").asDouble()) : null,
                        "实时可订价", hotel.path("bookingUrl").asText(null), name, List.of(),
                        "星级 " + hotel.path("starRating").asText("?") + "，距离 " + hotel.path("distanceInMeters").asText("?") + " 米"));
            }
        } catch (RuntimeException e) {
            return Map.of("error", "RollingGo 搜索失败：" + e.getMessage());
        }
        if (names.isEmpty()) {
            return Map.of("error", "这个地点附近没搜到符合条件的酒店，放宽预算或星级再试");
        }
        return quoteAll(names, city, in, out, rollingGo);
    }

    /** 已经有酒店名单时，同时查各平台价格。 */
    @Tool(description = """
            同时查指定几家酒店在携程、美团、飞猪上的价格（最多 5 家）。用户点名了具体酒店时用这个；
            按地点找酒店并比价用 findAndCompareHotels。查一次要十几秒，调用前先告诉用户正在比价。""")
    public Map<String, Object> compareHotelPrices(
            @ToolParam(description = "酒店完整名称列表") List<String> hotels,
            @ToolParam(description = "城市，例如：深圳") String city,
            @ToolParam(description = "入住日期 yyyy-MM-dd") String checkIn,
            @ToolParam(description = "离店日期 yyyy-MM-dd") String checkOut) {
        List<String> picked = hotels.stream().filter(h -> h != null && !h.isBlank()).limit(MAX_HOTELS).toList();
        return quoteAll(picked, city, date(checkIn), date(checkOut), List.of());
    }

    /**
     * 每家酒店 × 每个平台并行查（虚拟线程），按酒店分组返回；known 是已经拿到的报价（如 RollingGo），
     * 按酒店顺序放在各自结果的最前面。
     */
    private Map<String, Object> quoteAll(List<String> hotels, String city, LocalDate in, LocalDate out,
                                         List<HotelPriceSource.Quote> known) {
        List<HotelPriceSource> active = sources.stream().filter(HotelPriceSource::available).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, List<Future<HotelPriceSource.Quote>>> futures = new LinkedHashMap<>();
            for (String hotel : hotels) {
                List<Future<HotelPriceSource.Quote>> list = new ArrayList<>();
                for (HotelPriceSource source : active) {
                    list.add(executor.submit(() -> source.quote(hotel, city, in, out)));
                }
                futures.put(hotel, list);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            int index = 0;
            for (Map.Entry<String, List<Future<HotelPriceSource.Quote>>> entry : futures.entrySet()) {
                List<HotelPriceSource.Quote> quotes = new ArrayList<>();
                if (index < known.size()) {
                    quotes.add(known.get(index));
                }
                List<Future<HotelPriceSource.Quote>> list = entry.getValue();
                for (int i = 0; i < list.size(); i++) {
                    quotes.add(await(list.get(i), active.get(i).platform(), deadline));
                }
                results.add(Map.of("hotel", entry.getKey(), "quotes", quotes));
                index++;
            }
            executor.shutdownNow();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checkIn", String.valueOf(in));
        response.put("checkOut", String.valueOf(out));
        response.put("notConfigured", sources.stream().filter(s -> !s.available()).map(HotelPriceSource::platform).toList());
        response.put("results", results);
        if (!results.isEmpty()) {
            Map<String, Object> card = new LinkedHashMap<>(response);
            card.put("city", city);
            card.put("queriedAt", CardHub.now());
            if (cards.emit("hotels", card, summary(results))) {
                response.put("card", CardHub.SHOWN_HINT);
            }
        }
        return response;
    }

    /** 卡片的文字版：序号、酒店名、各平台里的最低价。 */
    @SuppressWarnings("unchecked")
    private static String summary(List<Map<String, Object>> results) {
        StringBuilder out = new StringBuilder("酒店比价：");
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);
            HotelPriceSource.Quote best = ((List<HotelPriceSource.Quote>) r.get("quotes")).stream()
                    .filter(q -> q.price() != null)
                    .min(Comparator.comparing(HotelPriceSource.Quote::price)).orElse(null);
            out.append(i == 0 ? "" : "；").append(i + 1).append(". ").append(r.get("hotel"))
                    .append(best == null ? " 无价格" : " 最低" + best.price() + "元/晚(" + best.platform() + ")");
        }
        return out.toString();
    }

    /** 拼 searchHotels 的参数，字段按 RollingGo 的工具定义。 */
    private String searchArgs(String place, String placeType, LocalDate in, LocalDate out, Double maxPrice,
                              Double minStar, int size) {
        Map<String, Object> checkInParam = new LinkedHashMap<>();
        if (in != null) {
            checkInParam.put("checkInDate", in.toString());
            checkInParam.put("stayNights", out != null && out.isAfter(in) ? (int) ChronoUnit.DAYS.between(in, out) : 1);
        }
        checkInParam.put("adultCount", 2);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("originQuery", place + " 附近的酒店");
        args.put("place", place);
        args.put("placeType", placeType);
        args.put("checkInParam", checkInParam);
        args.put("size", size);
        if (minStar != null) {
            args.put("filterOptions", Map.of("starRatings", List.of(minStar, 5.0)));
        }
        if (maxPrice != null) {
            args.put("hotelTags", Map.of("maxPricePerNight", maxPrice));
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** MCP 工具结果可能是内容数组 [{"type":"text","text":"{...}"}]，也可能直接是 JSON，统一取出里面的 JSON 对象。 */
    JsonNode unwrap(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            if (node.isArray() && !node.isEmpty() && node.get(0).has("text")) {
                node = objectMapper.readTree(node.get(0).path("text").asText());
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("RollingGo 返回格式看不懂：" + e.getMessage());
        }
    }

    /** 在总截止时间前取结果，超时或出错记为失败。 */
    private static HotelPriceSource.Quote await(Future<HotelPriceSource.Quote> future, String platform, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HotelPriceSource.Quote.failed(platform, "查询被中断");
        } catch (Exception e) {
            future.cancel(true);
            return HotelPriceSource.Quote.failed(platform, "查询超时");
        }
    }

    /** yyyy-MM-dd，空或格式不对返回 null。 */
    private static LocalDate date(String text) {
        try {
            return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
