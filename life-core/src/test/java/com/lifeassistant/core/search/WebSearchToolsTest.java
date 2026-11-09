package com.lifeassistant.core.search;

import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.conversation.TurnFlags;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 一轮对话里搜攻略的次数有上限；没配置时不把配置方法告诉用户。 */
class WebSearchToolsTest {

    private final WebSearchProvider fake = new WebSearchProvider() {
        public boolean available() {
            return true;
        }

        public List<SearchResult> search(String query, List<String> sites, int count, boolean recent) {
            return List.of();
        }
    };

    @Test
    void stopsAfterMaxSearchesPerTurn() {
        TurnFlags flags = new TurnFlags();
        WebSearchTools tools = new WebSearchTools(fake, flags);
        flags.start("c1", "西安攻略");
        CallRecorder.NOOP.withScope(new CallRecorder.Scope(1L, "c1", null), () -> {
            for (int i = 0; i < WebSearchTools.MAX_PER_TURN; i++) {
                assertThat(tools.searchGuides("西安", false)).containsKey("xiaohongshu");
            }
            Map<String, Object> blocked = tools.searchGuides("西安", false);
            assertThat(blocked.get("error").toString()).contains("本轮搜索次数已用完");
            return null;
        });
        flags.start("c1", "换个城市");
        CallRecorder.NOOP.withScope(new CallRecorder.Scope(1L, "c1", null),
                () -> assertThat(tools.searchGuides("成都", false)).containsKey("xiaohongshu"));
    }
}
