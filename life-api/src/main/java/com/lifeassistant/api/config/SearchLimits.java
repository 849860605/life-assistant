package com.lifeassistant.api.config;

import cn.dev33.satoken.stp.StpUtil;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.limit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 页面直接查价、查天气的接口（/api/v1/search/**、/api/v1/weather/**）的每人限流：这些接口大多不扣积分，背后是有额度的外部平台。
 * 每人每分钟 30 次、每天 600 次（正常翻页面、一个页面同时发几个请求都够用）；
 * refresh=true（跳过缓存重新查）每人每天 10 次，超过就悄悄用缓存；途牛（全站每天只有 50 次）每人每天 10 次。
 */
@Component
@RequiredArgsConstructor
public class SearchLimits implements HandlerInterceptor {

    static final int PER_MINUTE = 30;
    static final int PER_DAY = 600;
    static final int REFRESH_PER_DAY = 10;
    static final int TUNIU_PER_DAY = 10;

    private final RateLimiter limiter;

    /** 每个请求进来先记一次，超了报 429；没登录的交给登录拦截去报 401。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StpUtil.isLogin()) {
            return true;
        }
        long userId = StpUtil.getLoginIdAsLong();
        if (!limiter.perMinute("search:" + userId, PER_MINUTE)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "查得有点快，歇一分钟再试");
        }
        if (!limiter.perDay("search:" + userId, PER_DAY)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "今天查询的次数太多了，明天再来吧");
        }
        return true;
    }

    /** 这次能不能跳过缓存：要求刷新且今天还没用完刷新次数时返回 true，用完了返回 false（照样查，只是用缓存）。 */
    public boolean refresh(boolean requested) {
        if (!requested) {
            return false;
        }
        return limiter.perDay("search-refresh:" + StpUtil.getLoginIdAsLong(), REFRESH_PER_DAY);
    }

    /** 要查途牛前调用：这个人今天查途牛的次数用完了就报 429。 */
    public void tuniu() {
        if (!limiter.perDay("tuniu:" + StpUtil.getLoginIdAsLong(), TUNIU_PER_DAY)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "今天查途牛的次数用完了（每人每天 " + TUNIU_PER_DAY + " 次），明天再试，其他平台的价格不受影响");
        }
    }
}
