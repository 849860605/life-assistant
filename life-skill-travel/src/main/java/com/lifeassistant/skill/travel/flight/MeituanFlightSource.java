package com.lifeassistant.skill.travel.flight;

import com.lifeassistant.skill.travel.platform.MeituanHtClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美团查机票。返回 Markdown，每个航班一条链接：
 * "[南方航空CZ3171 深圳·宝安→北京·大兴 2026-11-05 07:30→10:45 3h15m 经济舱 ¥582](http://dpurl.cn/xx)"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeituanFlightSource implements FlightPriceSource {

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Pattern FLIGHT = Pattern.compile(
            "\\[([\\u4e00-\\u9fa5]+?)([A-Z0-9]{2}\\d{2,4})\\s+(\\S+?)→(\\S+?)\\s+\\d{4}-\\d{2}-\\d{2}\\s+"
                    + "(\\d{1,2}:\\d{2})→(\\d{1,2}:\\d{2})[^\\]]*?[¥￥](\\d{2,6})\\]\\((https?://[^)\\s]+)\\)");

    private final MeituanHtClient client;

    @Override
    public String platform() {
        return "美团";
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public Result search(String origin, String destination, LocalDate date) {
        try {
            return parse(client.query(origin, CN_DATE.format(date) + origin + "到" + destination + "的机票",
                    origin + "到" + destination + "机票"));
        } catch (RuntimeException e) {
            log.warn("美团查机票失败：{}", e.getMessage());
            return Result.failed(platform(), "美团暂时查不到：" + e.getMessage());
        }
    }

    /** 逐条匹配航班链接；同一航班出现多次只保留第一次。 */
    static Result parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Result.failed("美团", "美团没有返回内容");
        }
        List<Offer> offers = new ArrayList<>();
        Matcher m = FLIGHT.matcher(markdown);
        while (m.find()) {
            String flightNo = m.group(2);
            if (offers.stream().anyMatch(o -> o.flightNo().equals(flightNo))) {
                continue;
            }
            offers.add(new Offer(flightNo, m.group(1), m.group(5), m.group(6), m.group(3), m.group(4),
                    Integer.valueOf(m.group(7)), m.group(8), null));
        }
        return Result.of("美团", offers, null);
    }
}
