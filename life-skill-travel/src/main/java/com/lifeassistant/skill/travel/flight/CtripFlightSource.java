package com.lifeassistant.skill.travel.flight;

import com.lifeassistant.core.platform.CtripWendaoClient;
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
 * 携程问道查机票。返回 Markdown，每个航班一段："##### [深航 机型](<预订链接，含 dfltno=航班号>)"，
 * 下面是起飞、到达、价格。问法要写清"从 A 飞 B 的航班和价格"，问法太短时常查不到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CtripFlightSource implements FlightPriceSource {

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Pattern HEADER = Pattern.compile("#{3,6}\\s*\\[([^\\]]+)\\]\\(<?(https://m\\.ctrip\\.com/[^)>\\s]+)>?\\)");
    private static final Pattern FLIGHT_NO = Pattern.compile("dfltno=([A-Z0-9]+)");
    private static final Pattern DEP = Pattern.compile("起飞[：:]\\s*(.+?)\\s+(\\d{1,2}:\\d{2})");
    private static final Pattern ARR = Pattern.compile("到达[：:]\\s*(.+?)\\s+(\\d{1,2}:\\d{2})");
    private static final Pattern PRICE = Pattern.compile("[￥¥]\\s*(\\d{2,6})");

    private final CtripWendaoClient client;

    @Override
    public String platform() {
        return "携程";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Result search(String origin, String destination, LocalDate date) {
        try {
            return parse(client.query("帮我查" + CN_DATE.format(date) + "从" + origin + "飞" + destination
                    + "的航班和价格，请用md格式返回"));
        } catch (RuntimeException e) {
            log.warn("携程查机票失败：{}", e.getMessage());
            return Result.failed(platform(), "携程暂时查不到：" + e.getMessage());
        }
    }

    /** 按航班标题切段，每段取航班号（链接里的 dfltno）、起降时间和机场、价格。 */
    static Result parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Result.failed("携程", "携程没有返回内容");
        }
        List<Offer> offers = new ArrayList<>();
        Matcher header = HEADER.matcher(markdown);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (header.find()) {
            spans.add(new int[]{header.start(), header.end()});
            heads.add(new String[]{header.group(1), header.group(2)});
        }
        for (int i = 0; i < heads.size(); i++) {
            String url = heads.get(i)[1];
            Matcher no = FLIGHT_NO.matcher(url);
            if (!no.find()) {
                continue;
            }
            String body = markdown.substring(spans.get(i)[1], i + 1 < spans.size() ? spans.get(i + 1)[0] : markdown.length());
            Matcher dep = DEP.matcher(body);
            Matcher arr = ARR.matcher(body);
            Matcher price = PRICE.matcher(body);
            boolean hasDep = dep.find();
            boolean hasArr = arr.find();
            String airline = heads.get(i)[0].split("\\s+")[0];
            offers.add(new Offer(no.group(1), airline, hasDep ? dep.group(2) : null, hasArr ? arr.group(2) : null,
                    hasDep ? dep.group(1) : null, hasArr ? arr.group(1) : null,
                    price.find() ? Integer.valueOf(price.group(1)) : null, url, null));
        }
        String note = offers.isEmpty() && markdown.contains("未匹配") ? "携程没匹配到航班" : null;
        return Result.of("携程", offers, note);
    }
}
