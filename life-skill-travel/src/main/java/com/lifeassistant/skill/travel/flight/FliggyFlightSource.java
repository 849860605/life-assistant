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

    @Override
    public Result search(String origin, String destination, LocalDate date) {
        try {
            return parse(cli.run(List.of("search-flight", "--origin", origin, "--destination", destination,
                    "--dep-date", date.toString())));
        } catch (RuntimeException e) {
            log.warn("飞猪查机票失败：{}", e.getMessage());
            return Result.failed(platform(), e.getMessage());
        }
    }

    /** data.itemList[] 每项一个行程：取第一段的出发、最后一段的到达；多段的标为中转。 */
    static Result parse(JsonNode root) {
        List<Offer> offers = new ArrayList<>();
        for (JsonNode item : root.path("data").path("itemList")) {
            JsonNode journey = item.path("journeys").path(0);
            JsonNode segments = journey.path("segments");
            if (!segments.isArray() || segments.isEmpty()) {
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
                    price(item.path("ticketPrice").asText()), item.path("jumpUrl").asText(null), note));
        }
        return Result.of("飞猪", offers, null);
    }

    /** "2026-11-05 07:30:00" 取 07:30。 */
    private static String time(String dateTime) {
        return dateTime.length() >= 16 ? dateTime.substring(11, 16) : dateTime;
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
