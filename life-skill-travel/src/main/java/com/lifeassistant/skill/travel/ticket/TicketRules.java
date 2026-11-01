package com.lifeassistant.skill.travel.ticket;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.platform.CtripWendaoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 景点的预约规则、开放时间、票价说明，问携程问道（官方接口），按需查（每天额度有限），缓存 24 小时。
 * 免费景点也常常要实名预约，页面上靠这里的说明提示，不能因为免费就当成可以直接入园。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketRules {

    /** "- **开放时间**：08:30-18:00" */
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*]\\s*\\*\\*([^*]{2,12})\\*\\*\\s*[：:]\\s*(.+)$", Pattern.MULTILINE);
    /** 说明里提到要预约 */
    private static final Pattern NEED = Pattern.compile("需(要)?(提前|实名)?预约|实名预约|提前预约|须预约|必须预约|预约入[园馆]|分时预约");
    private static final Pattern NO_NEED = Pattern.compile("无需预约|不需要预约|免预约");

    private final CtripWendaoClient client;
    private final com.lifeassistant.core.cache.QueryCache queryCache;

    /**
     * items 是携程给的条目（开放时间、门票价格、预约方式……），summary 是第一句话；reservation 是从说明里看出来的：
     * NEED 要预约、NO_NEED 不用预约、UNKNOWN 看不出来。
     */
    public record Rules(String spot, Map<String, String> items, String summary, String reservation, String source,
                        String queriedAt) {
    }

    public Rules rules(String city, String spot, boolean refresh) {
        String name = TicketSearch.shortName(spot);
        if (name.isBlank() || name.length() > 30) {
            throw new BizException(ErrorCode.BAD_REQUEST, "景点名称不对");
        }
        return queryCache.get("spot-rules", city + "|" + name, java.time.Duration.ofHours(24), refresh, Rules.class, () -> {
            String text;
            try {
                text = client.query((city == null ? "" : city) + name + "的门票价格、开放时间、是否需要提前预约、怎么预约，请用md格式返回");
            } catch (RuntimeException e) {
                log.warn("携程查 {} 预约规则失败：{}", name, e.getMessage());
                throw new BizException(ErrorCode.EXTERNAL_SERVICE, (e instanceof IllegalStateException ? e.getMessage()
                        : "携程暂时查不到") + "，可以先看景区官方公众号或平台页面");
            }
            return parse(name, text);
        }, r -> !r.items().isEmpty() || !r.summary().isBlank());
    }

    static Rules parse(String spot, String text) {
        Map<String, String> items = new LinkedHashMap<>();
        if (text != null) {
            Matcher m = ITEM.matcher(text);
            while (m.find() && items.size() < 10) {
                items.putIfAbsent(m.group(1).trim(), m.group(2).replace("**", "").replaceAll("<([^>]+)>", "$1").trim());
            }
        }
        String all = text == null ? "" : text;
        String reservation = NO_NEED.matcher(all).find() && !NEED.matcher(all).find() ? "NO_NEED"
                : NEED.matcher(all).find() ? "NEED" : "UNKNOWN";
        String summary = all.strip().split("\\n")[0].replace("**", "").replaceAll("<([^>]+)>", "$1");
        return new Rules(spot, items, summary.length() > 200 ? summary.substring(0, 200) : summary, reservation,
                "携程问道", CardHub.now());
    }
}
