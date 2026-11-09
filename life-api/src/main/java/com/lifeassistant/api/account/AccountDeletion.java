package com.lifeassistant.api.account;

import cn.dev33.satoken.stp.StpUtil;
import com.lifeassistant.core.orchestrator.GraphCheckpoints;
import com.lifeassistant.core.storage.StorageService;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 用户自己注销账号：删掉这个人的全部内容（对话、对话断点、记忆、行程、日程和提醒、收藏、上传的文件、常用地址、
 * 推送和订阅设置、工具调用记录），账号行里的个人信息清空（见 UserService.anonymize），所有设备下线。
 * 付款订单、积分流水、会员记录按电子商务法要保留 3 年，只剩订单号、金额和时间，关联的账号已经没有个人信息。
 * 模型用量记录只有模型和 token 数，保留用来算成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletion {

    /** 按顺序删：先删引用别人的（提醒引用日程、对话消息随会话级联、行程的版本预订账单分享随行程级联） */
    private static final List<String> DELETES = List.of(
            "DELETE FROM t_reminder WHERE user_id = ?",
            "DELETE FROM t_notification WHERE user_id = ?",
            "DELETE FROM t_notify_channel WHERE user_id = ?",
            "DELETE FROM t_schedule_event WHERE user_id = ?",
            "DELETE FROM t_memory WHERE user_id = ?",
            "DELETE FROM t_tool_call_log WHERE user_id = ?",
            "DELETE FROM t_conversation WHERE user_id = ?",
            "DELETE FROM t_upload WHERE user_id = ?",
            "DELETE FROM t_api_key WHERE user_id = ?",
            "DELETE FROM t_brief_setting WHERE user_id = ?",
            "DELETE FROM t_feature_trial WHERE user_id = ?",
            "DELETE FROM t_favorite WHERE user_id = ?",
            "DELETE FROM t_trip WHERE user_id = ?",
            "DELETE FROM t_user_address WHERE user_id = ?",
            "DELETE FROM t_space_member WHERE user_id = ?");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UserService userService;
    private final GraphCheckpoints checkpoints;
    private final StorageService storage;

    /** 注销：数据库里的在一个事务里删完，文件和对话断点在事务提交后删（删失败只记日志，不影响注销）。 */
    public void delete(long userId) {
        List<String> conversations = jdbc.queryForList("SELECT id FROM t_conversation WHERE user_id = ?", String.class, userId);
        List<String> files = jdbc.queryForList(
                "SELECT storage_key FROM t_upload WHERE user_id = ? AND storage_key IS NOT NULL", String.class, userId);
        tx.executeWithoutResult(status -> {
            DELETES.forEach(sql -> jdbc.update(sql, userId));
            userService.anonymize(userId);
        });
        checkpoints.forget(conversations);
        for (String key : files) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("注销账号 {} 时删除文件 {} 失败：{}", userId, key, e.getMessage());
            }
        }
        StpUtil.logout(userId);
        log.info("账号 {} 已注销：删除 {} 个会话、{} 个文件", userId, conversations.size(), files.size());
    }
}
