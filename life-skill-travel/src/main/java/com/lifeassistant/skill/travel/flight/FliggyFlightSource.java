package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** 飞猪查机票：flyai search-flight，返回结构化 JSON（航段、时间、机场、价格、预订链接）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FliggyFlightSource implements FlightPriceSource {

    private final FliggyCli cli;

    @Override
    public String platform() {
        return "飞猪";
    }

    @Override
    public boolean available() {
        return cli.available();
    }

    /** 飞猪一次只给 10 班：按价格从低到高、直飞优先、起飞从早到晚各查一次再合并，覆盖面大一些 */
    private static final List<String> SORTS = List.of("3", "8", "6");

    @Override
    public Result search(String origin, String destination, LocalDate date) {
        return search(origin, destination, date, null);
    }

    /** 飞猪的舱位写法：只认中文，写 business 会被忽略、返回经济舱 */
    private static final java.util.Map<String, String> CABINS = java.util.Map.of("ECONOMY", "经济舱",
            "BUSINESS", "公务舱", "FIRST", "头等舱");

    @Override
    public Result search(String origin, String destination, LocalDate date, String cabin) {
        String seat = cabin == null ? null : CABINS.get(cabin.toUpperCase());
        List<Offer> merged = new ArrayList<>();
        String error = null;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Result>> futures = SORTS.stream().map(sort -> executor.submit(() ->
                    parse(cli.run(args(origin, destination, date, sort, seat)), seat))).toList();
            for (var future : futures) {
                try {
                    Result r = future.get();
                    if ("FAILED".equals(r.status())) {
                        error = r.note();
                    }
                    for (Offer o : r.offers()) {
                        if (merged.stream().noneMatch(x -> x.flightNo().equals(o.flightNo()))) {
                            merged.add(o);
                        }
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    error = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(platform(), "查询被中断");
        }
        if (merged.isEmpty() && error != null) {
            log.warn("飞猪查机票失败：{}", error);
            return Result.failed(platform(), error);
        }
        return Result.of(platform(), merged, null);
    }

    /** 某天飞猪上最便宜的一班（按价格从低到高查一次，取第一班），查不到返回空。 */
    public Offer lowest(String origin, String destination, LocalDate date) {
        // 空结果的重试由 FliggyCli 统一做
        return parse(cli.run(args(origin, destination, date, "3", null))).offers().stream()
                .filter(o -> o.price() != null).min(java.util.Comparator.comparing(Offer::price)).orElse(null);
    }

    private static List<String> args(String origin, String destination, LocalDate date, String sort, String seat) {
        List<String> args = new ArrayList<>(List.of("search-flight", "--origin", origin, "--destination", destination,
                "--dep-date", date.toString(), "--sort-type", sort));
        if (seat != null && !"经济舱".equals(seat)) {
            args.addAll(List.of("--seat-class-name", seat));
        }
        return args;
    }

    static Result parse(JsonNode root) {
        return parse(root, null);
    }

    /** data.itemList[] 每项一个行程：取第一段的出发、最后一段的到达；多段的标为中转。seat 不为空时只留这个舱位的。 */
    static Result parse(JsonNode root, String seat) {
        // 飞猪自己出错时 data 是 null，message 里是原因（如"搜索机票低价行业失败"）
        if ((root.path("data").isNull() || root.path("data").isMissingNode()) && root.hasNonNull("message")) {
            return Result.failed("飞猪", "飞猪这天的查询出错了（" + root.path("message").asText() + "），换个日期或稍后再试");
        }
        List<Offer> offers = new ArrayList<>();
        for (JsonNode item : root.path("data").path("itemList")) {
            JsonNode journey = item.path("journeys").path(0);
            JsonNode segments = journey.path("segments");
            if (!segments.isArray() || segments.isEmpty()) {
                continue;
            }
            String seatName = segments.get(0).path("seatClassName").asText("");
            if (seat != null && !seatName.isBlank() && !seatName.equals(seat)) {
                continue;
            }
            JsonNode first = segments.get(0);
            JsonNode last = segments.get(segments.size() - 1);
            String flightNo = StreamSupport.stream(segments.spliterator(), false)
                    .map(s -> s.path("marketingTransportNo").asText()).collect(Collectors.joining("/"));
            String airline = first.path("marketingTransportName").asText();
            String note = segments.size() > 1 ? "中转" : null;
            offers.add(new Offer(flightNo, airline.contains("|") ? airline.substring(airline.indexOf('|') + 1) : airline,
                    time(first.path("depDateTime").asText()), time(last.path("arrDateTime").asText()),
                    first.path("depStationShortName").asText() + first.path("depTerm").asText(),
                    last.path("arrStationShortName").asText() + last.path("arrTerm").asText(),
                    price(item.path("ticketPrice").asText()), item.path("jumpUrl").asText(null), note,
                    duration(journey.path("totalDuration").asText(item.path("totalDuration").asText(""))), null,
                    blank(first.path("depStationCode").asText(null)), blank(last.path("arrStationCode").asText(null)),
                    segments.size() > 1 ? blank(first.path("arrCityName").asText(null)) : null));
        }
        return Result.of("飞猪", offers, null);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** "2026-11-05 07:30:00" 取 07:30。 */
    private static String time(String dateTime) {
        return dateTime.length() >= 16 ? dateTime.substring(11, 16) : dateTime;
    }

    /** 全程分钟数，飞猪给的是字符串 "160"。 */
    private static Integer duration(String text) {
        try {
            return text == null || text.isBlank() ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "600.00" 取整数元。 */
    private static Integer price(String text) {
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
