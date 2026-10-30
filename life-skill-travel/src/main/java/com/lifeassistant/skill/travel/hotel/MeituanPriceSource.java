package com.lifeassistant.skill.travel.hotel;

import com.lifeassistant.core.platform.MeituanHtClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美团 ht-ai 查酒店价格：带房型级价格和预订短链；价格有时脱敏（￥XX），也可能混入团购价，只作参考。
 */
@Slf4j
@Component
public class MeituanPriceSource implements HotelPriceSource {

    /** 房型行："[**逸扉城景大床房[...]**](http://dpurl.cn/xx) ... **1晚 ￥866/晚**" */
    private static final Pattern ROOM = Pattern.compile(
            "\\[\\*\\*([^\\[\\]*]+?)(?:\\\\\\[[^\\]]*\\\\\\])?\\*\\*\\]\\((https?://[^)\\s]+)\\)[^\\n]*?￥(\\d{2,6})\\s*/晚");
    private static final Pattern FROM = Pattern.compile("约?\\s*(\\d{2,6})\\s*元起");
    private static final Pattern HOTEL = Pattern.compile("\\*\\*([^*\\n]{2,40}?(?:酒店|宾馆|民宿|客栈|公寓)[^*\\n]{0,20})\\*\\*");
    private static final int MAX_ROOMS = 4;

    private final MeituanHtClient client;

    public MeituanPriceSource(MeituanHtClient client) {
        this.client = client;
    }

    @Override
    public String platform() {
        return "美团";
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public Quote quote(String hotel, String city, LocalDate checkIn, LocalDate checkOut) {
        String when = checkIn == null ? "" : " " + checkIn + "入住" + (checkOut == null ? "" : " " + checkOut + "离店");
        try {
            return parse(client.query(city, hotel + when + " 日历房各房型价格，排除团购", hotel + "价格"));
        } catch (RuntimeException e) {
            log.warn("美团查价失败：{}", e.getMessage());
            return Quote.failed(platform(), "美团暂时查不到：" + e.getMessage());
        }
    }

    /** 从返回的 Markdown 里取前几个房型价格和链接；每晚最低价取"约 xxx 元起"和房型价里较小的。 */
    static Quote parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Quote.failed("美团", "美团没有返回内容");
        }
        List<String> rooms = new ArrayList<>();
        String firstLink = null;
        Integer lowest = null;
        Matcher room = ROOM.matcher(markdown);
        while (room.find()) {
            int price = Integer.parseInt(room.group(3));
            if (rooms.size() < MAX_ROOMS) {
                rooms.add(room.group(1).trim() + " ￥" + price);
            }
            if (firstLink == null) {
                firstLink = room.group(2);
            }
            lowest = lowest == null ? price : Math.min(lowest, price);
        }
        Matcher from = FROM.matcher(markdown);
        if (from.find()) {
            int fromPrice = Integer.parseInt(from.group(1));
            lowest = lowest == null ? fromPrice : Math.min(lowest, fromPrice);
        }
        Matcher hotel = HOTEL.matcher(markdown);
        String note = markdown.contains("XX") ? "部分价格被脱敏，需点链接查看" : null;
        return Quote.ok("美团", lowest, "约价，可能混入团购价，仅作参考", firstLink,
                hotel.find() ? hotel.group(1).trim() : null, rooms, note);
    }
}
