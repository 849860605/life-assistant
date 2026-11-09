package com.lifeassistant.core.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工具调用记录里有查询条件和返回内容（可能带用户说的话），隐私政策承诺保存 180 天：每天凌晨删掉更早的；
 * 用户删掉某个对话时，这个对话的记录也立即删掉。
 * 模型用量记录（t_llm_usage）只有模型和 token 数，不删，用来算成本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallLogCleaner {

    static final int KEEP_DAYS = 180;

    private final JdbcTemplate jdbc;

    /** 删掉某个对话的工具调用记录。 */
    public void forgetConversation(String conversationId) {
        jdbc.update("DELETE FROM t_tool_call_log WHERE conversation_id = ?", conversationId);
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Shanghai")
    @SchedulerLock(name = "toolCallLogCleaner", lockAtMostFor = "PT30M")
    public void clean() {
        int n = jdbc.update("DELETE FROM t_tool_call_log WHERE created_at < now() - make_interval(days => ?)", KEEP_DAYS);
        if (n > 0) {
            log.info("删掉 {} 条 {} 天前的工具调用记录", n, KEEP_DAYS);
        }
    }
}
