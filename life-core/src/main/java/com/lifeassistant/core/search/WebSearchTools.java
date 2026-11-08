package com.lifeassistant.core.search;

import com.lifeassistant.common.error.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 搜攻略：小红书笔记优先，再补全网结果。只拿搜索引擎给的标题、摘要和链接，不抓小红书页面本身。
 */
@Component
@RequiredArgsConstructor
public class WebSearchTools {

    private static final String XIAOHONGSHU = "xiaohongshu.com";
    /** 摘要截断长度，控制喂给模型的 Token */
    private static final int MAX_SUMMARY = 500;

    private final WebSearchProvider searchProvider;
    private final com.lifeassistant.core.conversation.TurnFlags turnFlags;

    /** 两次搜索：限定小红书 6 条 + 全网 6 条，按链接去重；没配置或出错时返回说明文字，不让对话失败。 */
    @Tool(description = """
            搜索旅行攻略和玩法经验（路线怎么排、避坑、美食、拍照点）。会分别返回小红书笔记和其他网站的结果，
            每条有标题、摘要和链接。只能基于摘要总结，摘要里没有的细节不要补编；引用时附上原链接。
            门票、开放时间、价格这类信息以官方为准，攻略里的可能过时。""")
    public Map<String, Object> searchGuides(
            @ToolParam(description = "搜索词，例如：西安两日游攻略、泰山夜爬 避坑") String query,
            @ToolParam(description = "是否只要最近一年的内容；季节性、时效性强的问题填 true", required = false) Boolean recentOnly) {
        if (turnFlags.planningOnly()) {
            return Map.of("skipped", "规划完整行程时先不搜攻略，按常识把行程写出来；用户问到玩法、避坑时再搜。");
        }
        if (!searchProvider.available()) {
            return Map.of("error", "联网搜索没有配置，告诉用户在 .env 里填 BOCHA_API_KEY");
        }
        boolean recent = Boolean.TRUE.equals(recentOnly);
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        result.put("xiaohongshu", safeSearch(query, List.of(XIAOHONGSHU), recent, seen));
        result.put("others", safeSearch(query, List.of(), recent, seen));
        return result;
    }

    /** 搜一次并整理成给模型看的结构；单次失败返回说明文字，另一路结果照常返回。 */
    private Object safeSearch(String query, List<String> sites, boolean recent, Set<String> seen) {
        try {
            return searchProvider.search(query, sites, 6, recent).stream()
                    .filter(r -> seen.add(r.url()))
                    .map(r -> Map.of(
                            "title", r.title(),
                            "url", r.url(),
                            "site", r.site(),
                            "published", r.published(),
                            "summary", truncate(r.summary().isBlank() ? r.snippet() : r.summary())))
                    .toList();
        } catch (BizException e) {
            return "搜索失败：" + e.getMessage();
        }
    }

    /** 超长摘要截断并加省略号。 */
    private static String truncate(String text) {
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY) + "…";
    }
}
