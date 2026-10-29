package com.lifeassistant.core.search;

import java.util.List;

/** 联网搜索数据源。现在是博查，以后换百炼联网搜索等只需要换实现。 */
public interface WebSearchProvider {

    /** 是否已配置好、可以调用 */
    boolean available();

    /**
     * 搜索网页。
     *
     * @param sites 只搜这些域名（如 xiaohongshu.com），为空表示全网
     */
    List<SearchResult> search(String query, List<String> sites, int count, boolean recentYearOnly);

    /** 一条搜索结果：summary 是较长的摘要，可能为空；published 是发布时间原文。 */
    record SearchResult(String title, String url, String site, String snippet, String summary, String published) {
    }
}
