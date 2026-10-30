package com.lifeassistant.core.food;

import com.lifeassistant.core.platform.MeituanHtClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美团查餐厅，数据是大众点评的。返回 Markdown，每家店一条链接加一段描述：
 * "[**店名（分店）**](http://dpurl.cn/xx)" 下面写评分、人均、距离和特色，链接是点评的店铺页。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeituanRestaurantSource implements RestaurantSource {

    private static final Pattern SHOP = Pattern.compile("\\[\\*{0,2}([^\\]*]+?)\\*{0,2}\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern RATING = Pattern.compile("(\\d\\.\\d)\\s*\\**\\s*分");
    private static final Pattern AVG = Pattern.compile("人均[^\\d\\n]{0,8}?(\\d{1,4})\\s*\\**\\s*元");
    private static final Pattern DISTANCE = Pattern.compile("约\\s*\\**\\s*(\\d+(?:\\.\\d+)?)\\s*\\**\\s*(公里|米)");

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
    public Result search(Query query) {
        try {
            return parse(client.query(query.city(), query.question(), query.shortQuestion()));
        } catch (RuntimeException e) {
            log.warn("美团查餐厅失败：{}", e.getMessage());
            return Result.failed(platform(), "美团暂时查不到：" + e.getMessage());
        }
    }

    /** 逐条匹配店铺链接，链接到下一个链接或小标题之间是这家店的描述，从里面取评分、人均、距离。 */
    static Result parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Result.failed("美团", "美团没有返回内容");
        }
        List<Shop> shops = new ArrayList<>();
        Matcher m = SHOP.matcher(markdown);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            heads.add(new String[]{m.group(1).trim(), m.group(2)});
        }
        for (int i = 0; i < heads.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : markdown.length();
            // 描述到空行或小标题为止，后面的"小贴士"不算这家店的
            String body = markdown.substring(spans.get(i)[1], end).stripLeading();
            for (String stop : List.of("\n\n", "\n#")) {
                int cut = body.indexOf(stop);
                if (cut >= 0) {
                    body = body.substring(0, cut);
                }
            }
            Matcher rating = RATING.matcher(body);
            Matcher avg = AVG.matcher(body);
            Matcher distance = DISTANCE.matcher(body);
            String text = body.replace("**", "").replace("</answer>", "").strip();
            shops.add(new Shop(heads.get(i)[0], rating.find() ? Double.valueOf(rating.group(1)) : null,
                    avg.find() ? Integer.valueOf(avg.group(1)) : null, null,
                    distance.find() ? "约" + distance.group(1) + distance.group(2) : null, null,
                    text.length() > 120 ? text.substring(0, 120) + "…" : text, heads.get(i)[1]));
        }
        return Result.of("美团", shops, markdown);
    }
}
