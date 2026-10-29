package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.skill.travel.platform.FliggyCli;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞猪 FlyAI 查酒店价格（通过 {@link FliggyCli} 调官方命令行工具）。价格是酒店级明码价，不是房型价。
 */
@Slf4j
@Component
public class FliggyPriceSource implements HotelPriceSource {

    private static final Pattern NUMBER = Pattern.compile("(\\d{2,6})");

    private final FliggyCli cli;

    public FliggyPriceSource(FliggyCli cli) {
        this.cli = cli;
    }

    @Override
    public String platform() {
        return "飞猪";
    }

    @Override
    public boolean available() {
        return cli.available();
    }

    /** 调 flyai search-hotel，按酒店名关键词搜，取名字最接近的一条。 */
    @Override
    public Quote quote(String hotel, String city, LocalDate checkIn, LocalDate checkOut) {
        List<String> args = new ArrayList<>(List.of("search-hotel", "--key-words", hotel));
        if (StringUtils.hasText(city)) {
            args.addAll(List.of("--dest-name", city));
        }
        if (checkIn != null && checkOut != null) {
            args.addAll(List.of("--check-in-date", checkIn.toString(), "--check-out-date", checkOut.toString()));
        }
        try {
            return parse(cli.run(args), hotel);
        } catch (RuntimeException e) {
            log.warn("飞猪查价失败：{}", e.getMessage());
            return Quote.failed(platform(), e.getMessage());
        }
    }

    /** 从 data.itemList 里挑名字和查询名一致或互相包含的一条；都不像就取第一条并提醒名字可能不一致。 */
    static Quote parse(JsonNode root, String hotel) {
        JsonNode items = root.path("data").path("itemList");
        if (!items.isArray() || items.isEmpty()) {
            return Quote.failed("飞猪", "飞猪没搜到这家酒店");
        }
        JsonNode picked = null;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            if (name.equals(hotel) || name.contains(hotel) || hotel.contains(name)) {
                picked = item;
                break;
            }
        }
        String note = null;
        if (picked == null) {
            picked = items.get(0);
            note = "飞猪上没找到同名酒店，这是最接近的一家，名字可能不一致";
        }
        Matcher price = NUMBER.matcher(picked.path("price").asText());
        return Quote.ok("飞猪", price.find() ? Integer.valueOf(price.group(1)) : null, "酒店级明码价，不是具体房型价",
                picked.path("detailUrl").asText(null), picked.path("name").asText(), List.of(), note);
    }
}
