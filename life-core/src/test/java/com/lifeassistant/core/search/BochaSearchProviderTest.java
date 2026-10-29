package com.lifeassistant.core.search;

import com.lifeassistant.core.config.LifeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 用本地假服务验证请求格式、结果解析，以及搜攻略工具的去重和未配置时的提示。 */
class BochaSearchProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsKeyAndSiteFilterAndParsesBingStyleResponse() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/web-search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"code":200,"msg":null,"data":{"webPages":{"value":[
                      {"name":"西安❗两日精华游","url":"https://www.xiaohongshu.com/explore/1","siteName":"小红书",
                       "snippet":"短摘要","summary":"陕历博一定要提前约，周一闭馆","datePublished":"2026-05-01"}]}}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BochaSearchProvider provider = provider("bocha-key", "http://127.0.0.1:" + server.getAddress().getPort());
        List<WebSearchProvider.SearchResult> results =
                provider.search("西安两日游攻略", List.of("xiaohongshu.com"), 6, true);

        assertThat(auth.get()).isEqualTo("Bearer bocha-key");
        assertThat(body.get()).contains("\"include\":\"xiaohongshu.com\"").contains("\"freshness\":\"oneYear\"")
                .contains("\"summary\":true");
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.title()).isEqualTo("西安❗两日精华游");
            assertThat(r.summary()).contains("周一闭馆");
            assertThat(r.site()).isEqualTo("小红书");
        });
    }

    @Test
    void toolReturnsHintWhenNotConfigured() {
        WebSearchTools tools = new WebSearchTools(provider("", "http://127.0.0.1:1"));
        assertThat(tools.searchGuides("西安两日游", null)).containsKey("error");
    }

    @Test
    void toolDeduplicatesAcrossTheTwoSearches() {
        WebSearchProvider.SearchResult note = new WebSearchProvider.SearchResult("笔记", "https://x/1", "小红书", "s", "", "");
        WebSearchProvider fake = new WebSearchProvider() {
            public boolean available() {
                return true;
            }

            public List<SearchResult> search(String query, List<String> sites, int count, boolean recent) {
                return sites.isEmpty() ? List.of(note, new SearchResult("知乎", "https://z/2", "知乎", "s", "", "")) : List.of(note);
            }
        };

        Map<String, Object> result = new WebSearchTools(fake).searchGuides("西安", false);

        assertThat((List<?>) result.get("xiaohongshu")).hasSize(1);
        assertThat((List<?>) result.get("others")).hasSize(1);
    }

    private static BochaSearchProvider provider(String key, String baseUrl) {
        LifeProperties properties = new LifeProperties();
        properties.getSearch().setBochaApiKey(key);
        properties.getSearch().setBochaBaseUrl(baseUrl);
        return new BochaSearchProvider(RestClient.builder(), properties);
    }
}
