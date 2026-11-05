package com.lifeassistant.core.member;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.setting.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 星络会员：按月购买，一次加 30 天；到期前再买从原到期日往后顺延，已过期从现在算。
 * 每次变动都记一条账（付费开通、后台调整）。价格和每天的对话次数存在 t_app_setting，后台可以改；
 * 下单金额只取这里的价格，不信前端传的。
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    /** 月度会员的商品代码，记在订单上 */
    public static final String MEMBER_MONTH = "MEMBER_MONTH";
    /** 买一次加多少天 */
    public static final int MONTH_DAYS = 30;
    /** 订单标题（支付宝收银台上显示） */
    public static final String MONTH_SUBJECT = "星络会员 · 1 个月";
    /** 账单来源：付费开通 */
    public static final String SOURCE_PAY = "PAY";
    /** 账单来源：后台调整 */
    public static final String SOURCE_ADMIN = "ADMIN";

    private static final String PRICE_KEY = "member.month.price";
    private static final String FREE_DAILY_KEY = "quota.free.daily";
    private static final String MEMBER_DAILY_KEY = "quota.member.daily";
    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("9.90");
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000");
    private static final int DEFAULT_FREE_DAILY = 20;
    private static final int DEFAULT_MEMBER_DAILY = 200;
    private static final int MAX_DAILY = 100_000;

    private final JdbcTemplate jdbc;
    private final AppSettingService settings;

    /** 会员状态：active 是现在是不是会员，expiresAt 是到期时间（从没开通过为空）。 */
    public record Status(boolean active, OffsetDateTime expiresAt) {
    }

    /** 一条会员账单：source 为 PAY / ADMIN，days 为负是扣减；adminName 是后台调整的管理员。 */
    public record LogEntry(long id, long userId, int days, String source, String outTradeNo, String adminName,
                           String note, OffsetDateTime expiresBefore, OffsetDateTime expiresAfter, OffsetDateTime createdAt) {
    }

    /** 每天能对话多少次：免费用户、会员。 */
    public record Quota(int freeDaily, int memberDaily) {
    }

    /** 后台账单概况：本月实收（已付款减已退款）、本月付费订单数、现在有效的会员数、累计实收。 */
    public record Stats(BigDecimal monthIncome, long monthPaidOrders, long activeMembers, BigDecimal totalIncome) {
    }

    /** 查某人的会员状态。 */
    public Status status(long userId) {
        List<OffsetDateTime> rows = jdbc.queryForList("SELECT expires_at FROM t_membership WHERE user_id = ?",
                OffsetDateTime.class, userId);
        OffsetDateTime expiresAt = rows.isEmpty() ? null : rows.get(0);
        return new Status(expiresAt != null && expiresAt.isAfter(OffsetDateTime.now()), expiresAt);
    }

    /** 付费开通：订单付款成功后调用，必须和订单状态更新在同一个事务里，才能保证一笔订单只加一次。 */
    @Transactional
    public OffsetDateTime extendByOrder(long userId, int days, String outTradeNo) {
        return extend(userId, days, SOURCE_PAY, outTradeNo, null, null);
    }

    /** 后台调整：加或扣会员天数，记下是哪个管理员、为什么。 */
    @Transactional
    public OffsetDateTime extendByAdmin(long userId, int days, long adminId, String note) {
        return extend(userId, days, SOURCE_ADMIN, null, adminId, note);
    }

    /**
     * 加会员天数并记账，返回新的到期时间：没到期从原到期日往后加，已过期或没开通过从现在算；days 为负往回扣。
     * 先锁住这个人的会员行再改，并发开通时到期时间和账单里的前后时间对得上。
     */
    private OffsetDateTime extend(long userId, int days, String source, String outTradeNo, Long adminId, String note) {
        List<OffsetDateTime> before = jdbc.queryForList("SELECT expires_at FROM t_membership WHERE user_id = ? FOR UPDATE",
                OffsetDateTime.class, userId);
        OffsetDateTime after = jdbc.queryForObject("""
                INSERT INTO t_membership (user_id, expires_at, updated_at) VALUES (?, now() + make_interval(days => ?), now())
                ON CONFLICT (user_id) DO UPDATE
                    SET expires_at = GREATEST(t_membership.expires_at, now()) + make_interval(days => ?), updated_at = now()
                RETURNING expires_at""", OffsetDateTime.class, userId, days, days);
        jdbc.update("""
                INSERT INTO t_membership_log (user_id, days, source, out_trade_no, admin_id, note, expires_before, expires_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", userId, days, source, outTradeNo, adminId, note,
                before.isEmpty() ? null : before.get(0), after);
        return after;
    }

    /** 某人的会员账单，新的在前。 */
    public List<LogEntry> logs(long userId, int limit) {
        return jdbc.query(LOG_SELECT + " WHERE l.user_id = ? ORDER BY l.id DESC LIMIT ?", LOG_ROW, userId, clamp(limit));
    }

    /** 所有人最近的会员账单，后台用。 */
    public List<LogEntry> recentLogs(int limit) {
        return jdbc.query(LOG_SELECT + " ORDER BY l.id DESC LIMIT ?", LOG_ROW, clamp(limit));
    }

    /** 后台账单概况：按北京时间算本月；实收 = 已付款订单金额 - 已成功的退款。 */
    public Stats stats() {
        String monthStart = "date_trunc('month', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'";
        BigDecimal monthPaid = money("SELECT coalesce(sum(total_amount), 0) FROM t_pay_order WHERE status = 'PAID' AND paid_at >= " + monthStart);
        BigDecimal monthRefund = money("SELECT coalesce(sum(refund_amount), 0) FROM t_pay_refund WHERE status = 'SUCCESS' AND updated_at >= " + monthStart);
        long monthOrders = count("SELECT count(*) FROM t_pay_order WHERE status = 'PAID' AND paid_at >= " + monthStart);
        long active = count("SELECT count(*) FROM t_membership WHERE expires_at > now()");
        BigDecimal totalPaid = money("SELECT coalesce(sum(total_amount), 0) FROM t_pay_order WHERE status = 'PAID'");
        BigDecimal totalRefund = money("SELECT coalesce(sum(refund_amount), 0) FROM t_pay_refund WHERE status = 'SUCCESS'");
        return new Stats(monthPaid.subtract(monthRefund), monthOrders, active, totalPaid.subtract(totalRefund));
    }

    /** 月度会员现在的价格（元，两位小数）；设置里的值坏了就用默认价。 */
    public BigDecimal monthPrice() {
        try {
            BigDecimal price = new BigDecimal(settings.get(PRICE_KEY, DEFAULT_PRICE.toPlainString()).trim());
            return price.signum() > 0 ? price.setScale(2, RoundingMode.HALF_UP) : DEFAULT_PRICE;
        } catch (NumberFormatException e) {
            return DEFAULT_PRICE;
        }
    }

    /** 改月度会员价格：0.01 到 10000 元，最多两位小数。 */
    public BigDecimal setMonthPrice(BigDecimal price) {
        if (price == null || price.compareTo(new BigDecimal("0.01")) < 0 || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "价格要在 0.01 到 10000 元之间，最多两位小数");
        }
        BigDecimal value = price.setScale(2, RoundingMode.UNNECESSARY);
        settings.set(PRICE_KEY, value.toPlainString());
        return value;
    }

    /** 每天的对话次数上限。 */
    public Quota quota() {
        return new Quota(intSetting(FREE_DAILY_KEY, DEFAULT_FREE_DAILY), intSetting(MEMBER_DAILY_KEY, DEFAULT_MEMBER_DAILY));
    }

    /** 改每天的对话次数上限：1 到 100000，会员不能比免费用户少。 */
    public Quota setQuota(int freeDaily, int memberDaily) {
        if (freeDaily < 1 || memberDaily < 1 || freeDaily > MAX_DAILY || memberDaily > MAX_DAILY) {
            throw new BizException(ErrorCode.BAD_REQUEST, "每天次数要在 1 到 100000 之间");
        }
        if (memberDaily < freeDaily) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会员的次数不能比免费用户少");
        }
        settings.set(FREE_DAILY_KEY, String.valueOf(freeDaily));
        settings.set(MEMBER_DAILY_KEY, String.valueOf(memberDaily));
        return quota();
    }

    private static final String LOG_SELECT = """
            SELECT l.*, a.username AS admin_name FROM t_membership_log l
            LEFT JOIN t_admin_user a ON a.id = l.admin_id""";

    private static final RowMapper<LogEntry> LOG_ROW = (rs, i) -> new LogEntry(rs.getLong("id"), rs.getLong("user_id"),
            rs.getInt("days"), rs.getString("source"), rs.getString("out_trade_no"), rs.getString("admin_name"),
            rs.getString("note"), rs.getObject("expires_before", OffsetDateTime.class),
            rs.getObject("expires_after", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class));

    private int intSetting(String key, int fallback) {
        try {
            int value = Integer.parseInt(settings.get(key, String.valueOf(fallback)).trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
