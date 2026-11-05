package com.lifeassistant.core.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台的统计和列表：总览数字、用户列表（带对话数、最近活跃）、单个用户概要、操作日志。
 * 只给计数和时间，不给对话正文、行程内容这类用户隐私。跨了出游技能的表，直接写 SQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final JdbcTemplate jdbc;

    /** 分页结果：total 是总条数，items 是这一页。 */
    public record Page<T>(long total, List<T> items) {
    }

    /**
     * 用户列表的一行：最近活跃取最近一次对话的更新时间；source 是 ADMIN 后台建的或 EMAIL 自己注册的；
     * memberUntil 是会员到期时间（从没开通过为空）。
     */
    public record UserRow(long id, String username, String nickname, String email, String source, int status,
                          OffsetDateTime createdAt, OffsetDateTime lastLoginAt, OffsetDateTime lastActiveAt,
                          long conversations, long trips, OffsetDateTime memberUntil) {
    }

    /**
     * 单个用户的概要：对话数、消息数、行程数、日程数、记忆数，最近 30 天模型调用次数、Token 和费用（元），
     * 最近一次对话时间。
     */
    public record UserSummary(long conversations, long messages, long trips, long events, long memories,
                              long llmCalls30d, long tokens30d, BigDecimal cost30d, OffsetDateTime lastActiveAt) {
    }

    /** 每天的数：对话（新建）、模型调用次数、Token、费用（元）。 */
    public record DayStat(String date, long conversations, long llmCalls, long tokens, BigDecimal cost) {
    }

    /**
     * 总览：用户数（启用的）、管理员数，今天和近 7 天新对话、消息、模型调用和费用，近 7 天工具调用成功率，
     * 以及近 7 天每天的趋势。
     */
    public record Overview(long users, long activeUsers, long admins, long conversationsToday, long conversations7d,
                           long messagesToday, long llmCallsToday, BigDecimal costToday, BigDecimal cost7d,
                           long tokens7d, long toolCalls7d, Double toolSuccessRate7d, long activeUsers7d,
                           List<DayStat> days) {
    }

    /** 操作日志的一行，带管理员用户名。 */
    public record OpLogRow(long id, long adminId, String adminName, String action, String target, String ip,
                           OffsetDateTime createdAt) {
    }

    /** 总览数字（日期按服务器所在时区 Asia/Shanghai 算"今天"）。 */
    public Overview overview() {
        String today = "date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'";
        String week = "(" + today + " - interval '6 days')";
        long users = count("SELECT count(*) FROM t_user");
        long activeUsers = count("SELECT count(*) FROM t_user WHERE status = 1");
        long admins = count("SELECT count(*) FROM t_admin_user WHERE status = 1");
        long convToday = count("SELECT count(*) FROM t_conversation WHERE created_at >= " + today);
        long conv7d = count("SELECT count(*) FROM t_conversation WHERE created_at >= " + week);
        long msgToday = count("SELECT count(*) FROM t_chat_message WHERE role = 'USER' AND created_at >= " + today);
        long llmToday = count("SELECT count(*) FROM t_llm_usage WHERE created_at >= " + today);
        BigDecimal costToday = money("SELECT coalesce(sum(cost), 0) FROM t_llm_usage WHERE created_at >= " + today);
        BigDecimal cost7d = money("SELECT coalesce(sum(cost), 0) FROM t_llm_usage WHERE created_at >= " + week);
        long tokens7d = count("SELECT coalesce(sum(prompt_tokens + completion_tokens), 0) FROM t_llm_usage WHERE created_at >= " + week);
        long tools7d = count("SELECT count(*) FROM t_tool_call_log WHERE created_at >= " + week);
        long toolsOk7d = count("SELECT count(*) FROM t_tool_call_log WHERE success AND created_at >= " + week);
        long active7d = count("SELECT count(DISTINCT user_id) FROM t_conversation WHERE updated_at >= " + week);
        return new Overview(users, activeUsers, admins, convToday, conv7d, msgToday, llmToday, costToday, cost7d,
                tokens7d, tools7d, tools7d == 0 ? null : Math.round(toolsOk7d * 1000.0 / tools7d) / 10.0, active7d,
                days());
    }

    /** 近 7 天每天的趋势，没数据的天补 0。 */
    private List<DayStat> days() {
        String sql = """
                WITH d AS (
                  SELECT generate_series((now() AT TIME ZONE 'Asia/Shanghai')::date - 6,
                                         (now() AT TIME ZONE 'Asia/Shanghai')::date, interval '1 day')::date AS day
                )
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                  (SELECT count(*) FROM t_conversation c
                     WHERE (c.created_at AT TIME ZONE 'Asia/Shanghai')::date = d.day) AS conversations,
                  (SELECT count(*) FROM t_llm_usage u
                     WHERE (u.created_at AT TIME ZONE 'Asia/Shanghai')::date = d.day) AS calls,
                  (SELECT coalesce(sum(u.prompt_tokens + u.completion_tokens), 0) FROM t_llm_usage u
                     WHERE (u.created_at AT TIME ZONE 'Asia/Shanghai')::date = d.day) AS tokens,
                  (SELECT coalesce(sum(u.cost), 0) FROM t_llm_usage u
                     WHERE (u.created_at AT TIME ZONE 'Asia/Shanghai')::date = d.day) AS cost
                FROM d ORDER BY d.day
                """;
        try {
            return jdbc.query(sql, (rs, i) -> new DayStat(rs.getString("day"), rs.getLong("conversations"),
                    rs.getLong("calls"), rs.getLong("tokens"), rs.getBigDecimal("cost")));
        } catch (DataAccessException e) {
            log.warn("后台趋势统计失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 用户分页列表，keyword 按用户名、昵称或邮箱模糊找；page 从 1 开始。 */
    public Page<UserRow> users(String keyword, int page, int size) {
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        String where = like == null ? "" : " WHERE u.username ILIKE ? OR u.nickname ILIKE ? OR u.email ILIKE ?";
        Object[] filter = like == null ? new Object[0] : new Object[]{like, like, like};
        long total = jdbc.queryForObject("SELECT count(*) FROM t_user u" + where, Long.class, filter);
        List<Object> args = new ArrayList<>(List.of(filter));
        args.add(size);
        args.add((long) (page - 1) * size);
        List<UserRow> rows = jdbc.query("""
                SELECT u.id, u.username, u.nickname, u.email, u.source, u.status, u.created_at, u.last_login_at,
                  (SELECT max(c.updated_at) FROM t_conversation c WHERE c.user_id = u.id) AS last_active_at,
                  (SELECT count(*) FROM t_conversation c WHERE c.user_id = u.id) AS conversations,
                  (SELECT count(*) FROM t_trip t WHERE t.user_id = u.id) AS trips,
                  (SELECT ms.expires_at FROM t_membership ms WHERE ms.user_id = u.id) AS member_until
                FROM t_user u""" + where + " ORDER BY u.id LIMIT ? OFFSET ?",
                (rs, i) -> new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("email"), rs.getString("source"), rs.getInt("status"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_login_at", OffsetDateTime.class),
                        rs.getObject("last_active_at", OffsetDateTime.class), rs.getLong("conversations"),
                        rs.getLong("trips"), rs.getObject("member_until", OffsetDateTime.class)), args.toArray());
        return new Page<>(total, rows);
    }

    /** 单个用户的概要，只有计数和时间。 */
    public UserSummary userSummary(long userId) {
        long conversations = count("SELECT count(*) FROM t_conversation WHERE user_id = ?", userId);
        long messages = count("""
                SELECT count(*) FROM t_chat_message m JOIN t_conversation c ON c.id = m.conversation_id
                WHERE c.user_id = ? AND m.role = 'USER'""", userId);
        long trips = count("SELECT count(*) FROM t_trip WHERE user_id = ?", userId);
        long events = count("SELECT count(*) FROM t_schedule_event WHERE user_id = ?", userId);
        long memories = count("SELECT count(*) FROM t_memory WHERE user_id = ?", userId);
        long calls = count("SELECT count(*) FROM t_llm_usage WHERE user_id = ? AND created_at >= now() - interval '30 days'", userId);
        long tokens = count("""
                SELECT coalesce(sum(prompt_tokens + completion_tokens), 0) FROM t_llm_usage
                WHERE user_id = ? AND created_at >= now() - interval '30 days'""", userId);
        BigDecimal cost = money("""
                SELECT coalesce(sum(cost), 0) FROM t_llm_usage
                WHERE user_id = ? AND created_at >= now() - interval '30 days'""", userId);
        OffsetDateTime lastActive = jdbc.queryForObject("SELECT max(updated_at) FROM t_conversation WHERE user_id = ?",
                OffsetDateTime.class, userId);
        return new UserSummary(conversations, messages, trips, events, memories, calls, tokens, cost, lastActive);
    }

    /** 操作日志分页，按时间倒序；action、adminId 为空不过滤。 */
    public Page<OpLogRow> opLogs(String action, Long adminId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" AND l.action = ?");
            args.add(action.trim());
        }
        if (adminId != null) {
            where.append(" AND l.admin_id = ?");
            args.add(adminId);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM t_admin_op_log l" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) (page - 1) * size);
        List<OpLogRow> rows = jdbc.query("""
                SELECT l.id, l.admin_id, a.username AS admin_name, l.action, l.target, l.ip, l.created_at
                FROM t_admin_op_log l LEFT JOIN t_admin_user a ON a.id = l.admin_id""" + where
                        + " ORDER BY l.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new OpLogRow(rs.getLong("id"), rs.getLong("admin_id"), rs.getString("admin_name"),
                        rs.getString("action"), rs.getString("target"), rs.getString("ip"),
                        rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
        return new Page<>(total, rows);
    }

    /** 操作日志里出现过的操作类型，前端筛选用。 */
    public List<String> opActions() {
        return jdbc.queryForList("SELECT DISTINCT action FROM t_admin_op_log ORDER BY action", String.class);
    }

    private long count(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            log.warn("后台统计失败：{}", e.getMessage());
            return 0;
        }
    }

    private BigDecimal money(String sql, Object... args) {
        try {
            BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
            return v == null ? BigDecimal.ZERO : v.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (DataAccessException e) {
            log.warn("后台费用统计失败：{}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
