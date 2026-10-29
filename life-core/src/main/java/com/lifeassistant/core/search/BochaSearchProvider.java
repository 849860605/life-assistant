package com.lifeassistant.core.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查 Web Search（https://open.bochaai.com）。国内搜索 API，支持按域名限定，
 * 返回格式兼容必应：结果在 webPages.value，每条有 name、url、snippet、summary、siteName、datePublished。
 */
@Slf4j
@Component
public class BochaSearchProvider implements WebSearchProvider {

    private final RestClient restClient;
    private final String apiKey;

    public BochaSearchProvider(RestClient.Builder externalRestClientBuilder, LifeProperties properties) {
        LifeProperties.Search config = properties.getSearch();
        this.apiKey = config.getBochaApiKey();
        this.restClient = externalRestClientBuilder.clone().baseUrl(config.getBochaBaseUrl()).build();
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(apiKey);
    }

    /** 调 /v1/web-search，开启长摘要；sites 用 | 拼成 include 参数。 */
    @Override
    public List<SearchResult> search(String query, List<String> sites, int count, boolean recentYearOnly) {
        if (!available()) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "联网搜索没有配置（BOCHA_API_KEY）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("summary", true);
        body.put("freshness", recentYearOnly ? "oneYear" : "noLimit");
        body.put("count", count);
        if (sites != null && !sites.isEmpty()) {
            body.put("include", String.join("|", sites));
        }
        try {
            JsonNode root = restClient.post().uri("/v1/web-search")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root);
        } catch (RestClientException e) {
            log.warn("博查搜索失败：{}", e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "联网搜索暂时不可用", e);
        }
    }

    /** 结果外层可能包一层 {code, data}，两种都兼容；code 不是 200 时抛 BizException。 */
    static List<SearchResult> parse(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        if (root.has("code") && root.path("code").asInt(200) != 200) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "联网搜索返回错误：" + root.path("msg").asText());
        }
        JsonNode data = root.has("data") ? root.path("data") : root;
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode page : data.path("webPages").path("value")) {
            results.add(new SearchResult(page.path("name").asText(), page.path("url").asText(),
                    page.path("siteName").asText(), page.path("snippet").asText(), page.path("summary").asText(),
                    page.path("datePublished").asText()));
        }
        return results;
    }
}
