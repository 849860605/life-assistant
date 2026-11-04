package com.lifeassistant.core.food;

import com.lifeassistant.core.platform.CtripWendaoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 携程问道查餐厅（携程美食林的数据）。返回 Markdown，每家店一个小标题"### <店名>（首选）"，
 * 下面是"- **评分**：4.6""- **人均**：100元""- **招牌菜**：……"这样的字段；没有店铺链接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CtripRestaurantSource implements RestaurantSource {

    private static final Pattern HEADER = Pattern.compile("(?m)^#{3,5}\\s*(.+?)\\s*$");
    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*[-*]\\s*\\*\\*([^*]+)\\*\\*\\s*[：:]\\s*(.+?)\\s*$");
    /** 店名后面的推荐标注，例如（首选）（强烈推荐）；带"店"字的是分店名，要保留 */
    private static final Pattern TAG = Pattern.compile("[（(]([^（()）]{1,6})[)）]\\s*$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private final CtripWendaoClient client;

    @Override
    public String platform() {
        return "携程";
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public Result search(Query query) {
        try {
            return parse(client.query(query.question() + "，请用md格式返回"));
        } catch (RuntimeException e) {
            log.warn("携程查餐厅失败：{}", e.getMessage());
            return Result.failed(platform(), "携程暂时查不到：" + e.getMessage());
        }
    }

    /** 按小标题切段；有评分、人均或招牌菜字段的段才算一家店，"场景速选"之类的段落跳过。 */
    static Result parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Result.failed("携程", "携程没有返回内容");
        }
        List<Shop> shops = new ArrayList<>();
        Matcher header = HEADER.matcher(markdown);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (header.find()) {
            spans.add(new int[]{header.start(), header.end()});
            names.add(header.group(1));
        }
        for (int i = 0; i < names.size(); i++) {
            String body = markdown.substring(spans.get(i)[1], i + 1 < spans.size() ? spans.get(i + 1)[0] : markdown.length());
            int section = body.indexOf("\n## ");
            if (section >= 0) {
                body = body.substring(0, section);
            }
            Map<String, String> fields = new HashMap<>();
            Matcher f = FIELD.matcher(body);
            while (f.find()) {
                fields.putIfAbsent(f.group(1).trim(), f.group(2).trim());
            }
            String dishes = fields.getOrDefault("招牌菜", fields.get("招牌"));
            if (!fields.containsKey("评分") && !fields.containsKey("人均") && dishes == null) {
                continue;
            }
            shops.add(new Shop(cleanName(names.get(i)), number(fields.get("评分"), true) instanceof Double d ? d : null,
                    number(fields.get("人均"), false) instanceof Integer n ? n : null, dishes,
                    fields.getOrDefault("位置", fields.get("地址")), fields.get("营业"), fields.get("亮点"), null));
        }
        return Result.of("携程", shops, markdown);
    }

    /** 去掉尖括号和末尾的推荐标注，保留分店名。 */
    static String cleanName(String raw) {
        String name = raw.replace("<", "").replace(">", "").replace("**", "").strip();
        Matcher tag = TAG.matcher(name);
        while (tag.find() && !tag.group(1).contains("店")) {
            name = name.substring(0, tag.start()).strip();
            tag = TAG.matcher(name);
        }
        return name;
    }

    /** 取字段里的第一个数字：评分返回 Double（超过 5 的不算评分），人均返回 Integer。 */
    private static Number number(String text, boolean rating) {
        if (text == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return null;
        }
        double value = Double.parseDouble(m.group(1));
        if (rating) {
            return value <= 5 ? value : null;
        }
        return (int) value;
    }
}
