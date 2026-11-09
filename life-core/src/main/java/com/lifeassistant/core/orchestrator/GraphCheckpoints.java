package com.lifeassistant.core.orchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 对话流程的断点（PostgresSaver 存在 graphthread / graphcheckpoint 表里，按会话 id 当线程名）里有对话内容，
 * 删会话、注销账号时要一起删掉。断点存内存（没用 Postgres）时没有这两张表，删的时候忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphCheckpoints {

    private final JdbcTemplate jdbc;

    /** 删掉这些会话的断点（graphcheckpoint 随 graphthread 级联删除）。 */
    public void forget(Collection<String> conversationIds) {
        if (conversationIds.isEmpty()) {
            return;
        }
        try {
            jdbc.update("DELETE FROM graphthread WHERE thread_name IN ("
                    + String.join(",", conversationIds.stream().map(id -> "?").toList()) + ")", conversationIds.toArray());
        } catch (DataAccessException e) {
            log.debug("删除对话断点跳过：{}", e.getMessage());
        }
    }
}
