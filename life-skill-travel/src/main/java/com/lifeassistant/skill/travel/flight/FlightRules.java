package com.lifeassistant.skill.travel.flight;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.platform.CtripWendaoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 某个航班的行李额和退改签说明，问携程问道（官方接口）。返回的是一般规则，具体费率随购票舱位变，
 * 页面上要注明"以购票页面为准"。一次十几秒，按航班和日期缓存 24 小时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightRules {

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Pattern FLIGHT_NO = Pattern.compile("[A-Z0-9]{2}\\d{1,4}");
    /** "- **免费托运行李额**：20KG，尺寸限制…" */
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*]\\s*\\*\\*([^*]{2,12})\\*\\*\\s*[：:]\\s*(.+)$", Pattern.MULTILINE);

    private final CtripWendaoClient client;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    /**
     * 查到的说明：items 是按携程的条目（机型、免费托运行李额、随身行李额、退改签规则……），summary 是第一句话；
     * 都没认出来时 items 为空、summary 是原文开头。
     */
    public record Rules(String flightNo, String date, Map<String, String> items, String summary, String source,
                        String queriedAt) {
    }

    public Rules rules(String flightNo, String origin, String destination, LocalDate date, boolean refresh) {
        String no = flightNo == null ? "" : flightNo.trim().toUpperCase().split("/")[0];
        if (!FLIGHT_NO.matcher(no).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "航班号不对");
        }
        return queryCache.get("flight-rules", no + "|" + date, java.time.Duration.ofHours(24), refresh, Rules.class, () -> {
            String text;
            try {
                text = client.query(CN_DATE.format(date) + clean(origin) + "飞" + clean(destination) + " " + no
                        + " 经济舱的退改签规则和免费托运行李额是多少");
            } catch (RuntimeException e) {
                log.warn("携程查 {} 退改规则失败：{}", no, e.getMessage());
                throw new BizException(ErrorCode.EXTERNAL_SERVICE, (e instanceof IllegalStateException ? e.getMessage()
                        : "携程暂时查不到") + "，可以先在平台预订页查看退改和行李");
            }
            return parse(no, date.toString(), text);
        }, r -> !r.items().isEmpty() || !r.summary().isBlank());
    }

    static Rules parse(String flightNo, String date, String text) {
        Map<String, String> items = new LinkedHashMap<>();
        if (text != null) {
            Matcher m = ITEM.matcher(text);
            while (m.find()) {
                String name = m.group(1).trim();
                // 航班信息、票价在比价表里已经有了，不重复
                if (!name.contains("航班信息") && !name.contains("票价")) {
                    items.putIfAbsent(name, m.group(2).replace("**", "").trim());
                }
            }
        }
        String summary = text == null ? "" : text.strip().split("\\n")[0].replace("**", "").replaceAll("<([^>]+)>", "$1");
        return new Rules(flightNo, date, items, summary.length() > 200 ? summary.substring(0, 200) : summary,
                "携程问道", CardHub.now());
    }

    private static String clean(String city) {
        return city == null ? "" : city.replaceAll("[^\\p{IsHan}A-Za-z]", "");
    }
}
