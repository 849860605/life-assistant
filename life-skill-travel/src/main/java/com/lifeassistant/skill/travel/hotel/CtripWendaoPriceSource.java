package com.lifeassistant.skill.travel.hotel;

import com.lifeassistant.skill.travel.platform.CtripWendaoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 携程问道查酒店价格：能拿到最低参考价和带日期的预订链接，各房型实时价经常拿不到。
 */
@Slf4j
@Component
public class CtripWendaoPriceSource implements HotelPriceSource {

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    /** 优先取"最低/起步/参考"后面的价格，格式有"最低价格：809元/晚""约809元/晚起"等 */
    private static final Pattern LOWEST = Pattern.compile("(?:最低|起步|参考)[^0-9\\n]{0,12}?(\\d{2,6})\\s*元");
    private static final Pattern ANY_PRICE = Pattern.compile("(\\d{2,6})\\s*元\\s*/?\\s*晚");
    private static final Pattern LINK = Pattern.compile("\\(<?(https://m\\.ctrip\\.com/[^)>\\s]+)>?\\)");
    private static final Pattern TITLE = Pattern.compile("(?m)^#{2,3}\\s*<?([^<>\\n]+?)>?\\s*$");

    private final CtripWendaoClient client;

    public CtripWendaoPriceSource(CtripWendaoClient client) {
        this.client = client;
    }

    @Override
    public String platform() {
        return "携程";
    }

    /** 匿名也能用，所以始终可用。 */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Quote quote(String hotel, String city, LocalDate checkIn, LocalDate checkOut) {
        try {
            return parse(client.query("查询" + (city == null ? "" : city) + hotel + dates(checkIn, checkOut)
                    + "的价格，请用md格式返回"));
        } catch (RuntimeException e) {
            log.warn("携程问道查价失败：{}", e.getMessage());
            return Quote.failed(platform(), "携程问道暂时查不到：" + e.getMessage());
        }
    }

    /** 从问道返回的 Markdown 里取最低参考价、预订链接和酒店名。 */
    static Quote parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Quote.failed("携程", "携程问道没有返回内容");
        }
        Integer price = firstNumber(LOWEST, markdown);
        if (price == null) {
            price = firstNumber(ANY_PRICE, markdown);
        }
        Matcher link = LINK.matcher(markdown);
        Matcher title = TITLE.matcher(markdown);
        return Quote.ok("携程", price, "最低参考价，实际以预订页为准", link.find() ? link.group(1) : null,
                title.find() ? title.group(1).trim() : null, List.of(), null);
    }

    /** 拼"2026年10月30日入住、10月31日离店"；日期不全时不带。 */
    static String dates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            return "";
        }
        return CN_DATE.format(checkIn) + "入住、" + CN_DATE.format(checkOut) + "离店";
    }

    private static Integer firstNumber(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

}
