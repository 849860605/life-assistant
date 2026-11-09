package com.lifeassistant.core.member;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.setting.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分：用多少扣多少（1 元 = 100 积分）。按"事情"定价（对话、规划行程、修改行程、比价、航班动态），价格后台可改。
 * 新用户第一次用到时送一笔注册积分；每天另送一点（当天有效、不累积），扣费时先扣每天送的、再扣余额。
 * 会员（以前按月买的）到期前不扣积分。每次改动都先锁住这个人的账户行再改，并发扣费时余额和流水对得上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    /** 计费的事情 */
    public static final String CHAT = "chat";
    public static final String PLAN = "plan";
    public static final String EDIT = "edit";
    public static final String COMPARE = "compare";
    public static final String FLIGHT = "flight";
    /** 各项的中文名，前端和后台显示用 */
    public static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put(CHAT, "和星络对话（每条）");
        FEATURES.put(PLAN, "规划一个完整行程");
        FEATURES.put(EDIT, "修改行程");
        FEATURES.put(COMPARE, "机票、酒店比价（每次）");
        FEATURES.put(FLIGHT, "航班实时动态（每次）");
    }

    /** 积分包的商品编码前缀：CREDIT_3300 表示 3300 积分，到账按编码里的数，以后改了积分包也不影响旧订单 */
    public static final String PRODUCT_PREFIX = "CREDIT_";
    private static final Map<String, Integer> DEFAULT_PRICES = Map.of(CHAT, 5, PLAN, 60, EDIT, 20, COMPARE, 15, FLIGHT, 100);
    private static final String DEFAULT_PACKS = "6:600,30:3300,68:8000";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final AppSettingService settings;
    private final MembershipService membership;

    /** 一个积分包：多少钱、给多少积分、比原价多送几成。 */
    public record Pack(String code, BigDecimal yuan, int credits, int bonusPercent) {
    }

    /** 价格和赠送规则。 */
    public record Plan(Map<String, Integer> prices, int signup, int daily, List<Pack> packs) {
    }

    /** 某人的积分：余额（可为负）、今天送的还剩多少、今天送多少、会员是否有效（有效时不扣）、加上价格表。 */
    public record View(long balance, int dailyLeft, int daily, boolean member, OffsetDateTime memberExpiresAt, Plan plan) {
    }

    /** 一次扣费的结果：扣了多少（会员或免费的为 0）、其中每天送的抵了多少、扣完后的余额和今天剩余；ledgerId 用来退回。 */
    public record Charge(Long ledgerId, long userId, String feature, int amount, int fromDaily, long balanceAfter, int dailyLeft) {
        /** 没扣（会员、价格是 0） */
        public static Charge free(long userId, String feature) {
            return new Charge(null, userId, feature, 0, 0, 0, 0);
        }
    }

    /** 一条流水。 */
    public record Entry(long id, String kind, String feature, int amount, int fromDaily, long balanceAfter, String ref,
                        String note, OffsetDateTime createdAt) {
    }

    // ========== 查询 ==========

    /** 某人的积分情况；第一次用到时开户并送注册积分。 */
    @Transactional
    public View view(long userId) {
        Account a = lock(userId);
        MembershipService.Status m = membership.status(userId);
        Plan plan = plan();
        return new View(a.balance(), a.dailyLeft(), plan.daily(), m.active(), m.active() ? m.expiresAt() : null, plan);
    }

    /**
     * 开户但不送注册积分（同一 IP 当天注册太多个号时用），每天的赠送照常；已经开过户的不变。
     * 注册积分本来是第一次用到时才送，先把户开好，之后就不会再送了。
     */
    public void openWithoutSignupBonus(long userId) {
        jdbc.update("""
                INSERT INTO t_credit_account (user_id, balance, daily_date, daily_left) VALUES (?, 0, ?, ?)
                ON CONFLICT (user_id) DO NOTHING""", userId, today(), plan().daily());
    }

    /** 某人最近的流水，新的在前。 */
    public List<Entry> ledger(long userId, int limit) {
        return jdbc.query("""
                SELECT id, kind, feature, amount, from_daily, balance_after, ref, note, created_at
                FROM t_credit_ledger WHERE user_id = ? ORDER BY id DESC LIMIT ?""",
                (rs, i) -> new Entry(rs.getLong("id"), rs.getString("kind"), rs.getString("feature"), rs.getInt("amount"),
                        rs.getInt("from_daily"), rs.getLong("balance_after"), rs.getString("ref"), rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId, Math.max(1, Math.min(limit, 200)));
    }

    /** 这件事现在多少积分。 */
    public int price(String feature) {
        return plan().prices().getOrDefault(feature, 0);
    }

    // ========== 扣费、退回 ==========

    /**
     * 扣一件事的积分：会员有效期内、价格为 0 时不扣。先扣今天送的，再扣余额。
     * overdraft 为 false 时余额不够直接报 402（还没开始干活的，比如发消息、查航班动态）；
     * 为 true 时允许扣成负数（AI 已经把行程做完了才扣的），下次用之前要先补上。
     */
    @Transactional
    public Charge spend(long userId, String feature, String ref, boolean overdraft) {
        int price = price(feature);
        if (price <= 0 || membership.status(userId).active()) {
            return Charge.free(userId, feature);
        }
        Account a = lock(userId);
        long available = a.dailyLeft() + Math.max(0, a.balance());
        if (!overdraft && available < price) {
            throw new BizException(ErrorCode.CREDIT_NOT_ENOUGH, notEnough(feature, price, a));
        }
        int fromDaily = Math.min(a.dailyLeft(), price);
        long balance = a.balance() - (price - fromDaily);
        int dailyLeft = a.dailyLeft() - fromDaily;
        save(userId, balance, dailyLeft);
        Long id = insert(userId, "SPEND", feature, -price, fromDaily, balance, cut(ref, 80), null, null);
        return new Charge(id, userId, feature, price, fromDaily, balance, dailyLeft);
    }

    /** 退回一次扣费（这次没干成，比如回答出错、航班没查到）：今天送的退回今天，其余退回余额。 */
    @Transactional
    public void refund(Charge charge) {
        if (charge == null || charge.ledgerId() == null || charge.amount() <= 0) {
            return;
        }
        Account a = lock(charge.userId());
        Integer done = jdbc.queryForObject("SELECT count(*) FROM t_credit_ledger WHERE kind = 'RETURN' AND ref = ?",
                Integer.class, "L" + charge.ledgerId());
        if (done != null && done > 0) {
            return;
        }
        LocalDate spentDay = jdbc.queryForObject("SELECT (created_at AT TIME ZONE 'Asia/Shanghai')::date FROM t_credit_ledger WHERE id = ?",
                LocalDate.class, charge.ledgerId());
        int backToDaily = today().equals(spentDay) ? charge.fromDaily() : 0;
        long balance = a.balance() + (charge.amount() - backToDaily);
        int dailyLeft = Math.min(a.dailyLeft() + backToDaily, plan().daily());
        save(charge.userId(), balance, dailyLeft);
        insert(charge.userId(), "RETURN", charge.feature(), charge.amount(), backToDaily, balance, "L" + charge.ledgerId(),
                "没有完成，退回", null);
    }

    // ========== 购买、后台 ==========

    /** 支付成功后到账，同一个订单只到账一次；返回这次是不是真的到账了。须在支付的事务里调用。 */
    @Transactional
    public boolean grantPurchase(long userId, int credits, String outTradeNo) {
        Account a = lock(userId);
        Integer done = jdbc.queryForObject("SELECT count(*) FROM t_credit_ledger WHERE kind = 'PURCHASE' AND ref = ?",
                Integer.class, outTradeNo);
        if (done != null && done > 0) {
            return false;
        }
        long balance = a.balance() + credits;
        save(userId, balance, a.dailyLeft());
        insert(userId, "PURCHASE", null, credits, 0, balance, outTradeNo, "购买 " + credits + " 积分", null);
        log.info("积分已到账 userId={} credits={} outTradeNo={}", userId, credits, outTradeNo);
        return true;
    }

    /** 订单退款成功后扣回这笔积分（已经用掉的就扣成负数）；同一个订单只扣一次。 */
    @Transactional
    public void revokePurchase(long userId, int credits, String outTradeNo) {
        Account a = lock(userId);
        Integer done = jdbc.queryForObject("SELECT count(*) FROM t_credit_ledger WHERE kind = 'REFUND' AND ref = ?",
                Integer.class, outTradeNo);
        if (done != null && done > 0) {
            return;
        }
        long balance = a.balance() - credits;
        save(userId, balance, a.dailyLeft());
        insert(userId, "REFUND", null, -credits, 0, balance, outTradeNo, "订单退款，扣回 " + credits + " 积分", null);
    }

    /** 后台加减积分（正数加、负数扣），必须写原因。 */
    @Transactional
    public long adjustByAdmin(long userId, int delta, long adminId, String note) {
        if (delta == 0 || Math.abs(delta) > 1_000_000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "调整的积分要在 ±1000000 以内，不能是 0");
        }
        if (note == null || note.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请写一下为什么调整");
        }
        Account a = lock(userId);
        long balance = a.balance() + delta;
        save(userId, balance, a.dailyLeft());
        insert(userId, "ADMIN", null, delta, 0, balance, null, cut(note.trim(), 200), adminId);
        return balance;
    }

    /** 某个订单商品编码对应多少积分（不是积分包时返回 0）。 */
    public static int creditsOf(String productCode) {
        if (productCode == null || !productCode.startsWith(PRODUCT_PREFIX)) {
            return 0;
        }
        try {
            return Integer.parseInt(productCode.substring(PRODUCT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 按编码找积分包（下单时用），找不到报错。 */
    public Pack pack(String code) {
        return plan().packs().stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "这个积分包已经下架了，刷新一下再选"));
    }

    // ========== 价格设置 ==========

    /** 现在的价格和赠送规则；设置坏了的项用默认值。 */
    public Plan plan() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        for (String f : FEATURES.keySet()) {
            prices.put(f, intSetting("credit.price." + f, DEFAULT_PRICES.get(f)));
        }
        return new Plan(prices, intSetting("credit.signup", 300), intSetting("credit.daily", 10),
                parsePacks(settings.get("credit.packs", DEFAULT_PACKS)));
    }

    /** 后台改价格和赠送规则：价格 0~10000 积分，注册送 0~100000，每天送 0~1000；积分包写成"元:积分"，逗号隔开，最多 6 个。 */
    public Plan setPlan(Map<String, Integer> prices, int signup, int daily, String packs) {
        for (Map.Entry<String, Integer> e : prices.entrySet()) {
            if (!FEATURES.containsKey(e.getKey()) || e.getValue() == null || e.getValue() < 0 || e.getValue() > 10_000) {
                throw new BizException(ErrorCode.BAD_REQUEST, "价格要在 0 到 10000 积分之间");
            }
        }
        if (signup < 0 || signup > 100_000 || daily < 0 || daily > 1000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "注册赠送 0~100000、每天赠送 0~1000 积分");
        }
        List<Pack> parsed = parsePacks(packs);
        if (parsed.isEmpty() || parsed.size() > 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "积分包写成\"元:积分\"，比如 6:600,30:3300，1 到 6 个");
        }
        prices.forEach((k, v) -> settings.set("credit.price." + k, String.valueOf(v)));
        settings.set("credit.signup", String.valueOf(signup));
        settings.set("credit.daily", String.valueOf(daily));
        settings.set("credit.packs", packs.trim());
        return plan();
    }

    /** 后台看板：本月卖出多少积分、各项用了多少次多少积分。 */
    public Map<String, Object> monthStats() {
        String since = "date_trunc('month', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sold", jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM t_credit_ledger WHERE kind = 'PURCHASE' AND created_at >= " + since, Long.class));
        List<Map<String, Object>> spent = jdbc.queryForList("""
                SELECT feature, count(*) AS times, -sum(amount) AS credits, -sum(amount) + sum(from_daily) AS paid_credits
                FROM t_credit_ledger WHERE kind = 'SPEND' AND created_at >= """ + since + " GROUP BY feature ORDER BY 3 DESC");
        out.put("spent", spent);
        return out;
    }

    // ========== 内部 ==========

    private record Account(long balance, int dailyLeft) {
    }

    /** 锁住账户行（没有就先开户、送注册积分），并把每天的赠送刷新到今天。 */
    private Account lock(long userId) {
        Plan plan = plan();
        int created = jdbc.update("""
                INSERT INTO t_credit_account (user_id, balance, daily_date, daily_left) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING""", userId, plan.signup(), today(), plan.daily());
        if (created > 0 && plan.signup() > 0) {
            insert(userId, "SIGNUP", null, plan.signup(), 0, plan.signup(), null, "新用户赠送", null);
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT balance, daily_date, daily_left FROM t_credit_account WHERE user_id = ? FOR UPDATE", userId);
        long balance = ((Number) row.get("balance")).longValue();
        Object date = row.get("daily_date");
        LocalDate day = date instanceof java.sql.Date d ? d.toLocalDate() : date instanceof LocalDate l ? l : null;
        int dailyLeft = ((Number) row.get("daily_left")).intValue();
        if (!today().equals(day)) {
            dailyLeft = plan.daily();
            jdbc.update("UPDATE t_credit_account SET daily_date = ?, daily_left = ?, updated_at = now() WHERE user_id = ?",
                    today(), dailyLeft, userId);
        }
        return new Account(balance, dailyLeft);
    }

    private void save(long userId, long balance, int dailyLeft) {
        jdbc.update("UPDATE t_credit_account SET balance = ?, daily_left = ?, daily_date = ?, updated_at = now() WHERE user_id = ?",
                balance, dailyLeft, today(), userId);
    }

    private Long insert(long userId, String kind, String feature, int amount, int fromDaily, long balanceAfter, String ref,
                        String note, Long adminId) {
        return jdbc.queryForObject("""
                INSERT INTO t_credit_ledger (user_id, kind, feature, amount, from_daily, balance_after, ref, note, admin_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                userId, kind, feature, amount, fromDaily, balanceAfter, ref, note, adminId);
    }

    private String notEnough(String feature, int price, Account a) {
        long have = a.dailyLeft() + Math.max(0, a.balance());
        String what = switch (feature) {
            case CHAT -> "发一条消息";
            case FLIGHT -> "查一次航班动态";
            case COMPARE -> "比一次价";
            default -> "这次";
        };
        return "积分不够了：" + what + "要 " + price + " 积分，现在还有 " + have + "。买一点积分就能继续用"
                + (a.balance() < 0 ? "（上次规划行程透支了 " + (-a.balance()) + "）" : "");
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(settings.get(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "6:600,30:3300" → 积分包；多送几成按最小包的单价算。写错的项跳过。 */
    static List<Pack> parsePacks(String text) {
        List<Pack> out = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("[,，]")) {
            String[] kv = part.trim().split("[:：]");
            if (kv.length != 2) {
                continue;
            }
            try {
                BigDecimal yuan = new BigDecimal(kv[0].trim());
                int credits = Integer.parseInt(kv[1].trim());
                if (yuan.compareTo(new BigDecimal("0.01")) >= 0 && yuan.compareTo(new BigDecimal("10000")) <= 0
                        && yuan.stripTrailingZeros().scale() <= 2 && credits > 0 && credits <= 10_000_000) {
                    out.add(new Pack(PRODUCT_PREFIX + credits, yuan.setScale(2, java.math.RoundingMode.HALF_UP), credits, 0));
                }
            } catch (NumberFormatException ignored) {
                // 写错的跳过
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        double base = out.stream().mapToDouble(p -> p.credits() / p.yuan().doubleValue()).min().orElse(100);
        return out.stream().map(p -> new Pack(p.code(), p.yuan(), p.credits(),
                (int) Math.round((p.credits() / p.yuan().doubleValue() / base - 1) * 100))).toList();
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
