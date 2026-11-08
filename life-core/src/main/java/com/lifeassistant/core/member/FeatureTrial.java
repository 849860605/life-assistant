package com.lifeassistant.core.member;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.setting.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会员功能的免费试用：免费用户对某个功能总共能用几次（后台可改），会员不限。
 * 次数存数据库（t_feature_trial），用一次记一次；并发时用 UPDATE ... WHERE used < limit 保证不超。
 */
@Service
@RequiredArgsConstructor
public class FeatureTrial {

    /** 航班实时动态 */
    public static final String FLIGHT_STATUS = "flight_status";
    private static final int MAX_TRIAL = 100;

    private final JdbcTemplate jdbc;
    private final AppSettingService settings;
    private final MembershipService membership;

    /** 某人对某功能的情况：是不是会员、免费试用总共几次、还剩几次（会员时剩余无意义）。 */
    public record Usage(boolean member, int total, int left) {
    }

    public Usage usage(long userId, String feature) {
        boolean member = membership.status(userId).active();
        int total = limit(feature);
        return new Usage(member, total, Math.max(0, total - used(userId, feature)));
    }

    /**
     * 要用一次：会员直接放行；免费用户扣一次试用，用完了报 402，提示开通会员。
     * 返回扣完之后的情况。
     */
    public Usage consume(long userId, String feature, String featureName) {
        boolean member = membership.status(userId).active();
        int total = limit(feature);
        if (member) {
            return new Usage(true, total, total);
        }
        jdbc.update("INSERT INTO t_feature_trial (user_id, feature, used) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
                userId, feature);
        int updated = jdbc.update("""
                UPDATE t_feature_trial SET used = used + 1, updated_at = now()
                WHERE user_id = ? AND feature = ? AND used < ?""", userId, feature, total);
        if (updated == 0) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, featureName + "的 " + total + " 次免费试用已经用完了，开通会员可以随时查看");
        }
        return new Usage(false, total, Math.max(0, total - used(userId, feature)));
    }

    /** 退回一次试用（查询失败、没查到时不该算用户的次数）；会员不用退。 */
    public void refund(long userId, String feature) {
        if (membership.status(userId).active()) {
            return;
        }
        jdbc.update("UPDATE t_feature_trial SET used = used - 1, updated_at = now() WHERE user_id = ? AND feature = ? AND used > 0",
                userId, feature);
    }

    /** 某功能的免费试用次数（后台设置，默认 2）。 */
    public int limit(String feature) {
        try {
            return Integer.parseInt(settings.get("trial." + feature, "2"));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** 改免费试用次数：0 到 100（0 表示不给试用）。 */
    public int setLimit(String feature, int limit) {
        if (limit < 0 || limit > MAX_TRIAL) {
            throw new BizException(ErrorCode.BAD_REQUEST, "试用次数要在 0 到 " + MAX_TRIAL + " 之间");
        }
        settings.set("trial." + feature, String.valueOf(limit));
        return limit;
    }

    private int used(long userId, String feature) {
        List<Integer> rows = jdbc.queryForList("SELECT used FROM t_feature_trial WHERE user_id = ? AND feature = ?",
                Integer.class, userId, feature);
        return rows.isEmpty() ? 0 : rows.get(0);
    }
}
