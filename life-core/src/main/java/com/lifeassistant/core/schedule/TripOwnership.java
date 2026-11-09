package com.lifeassistant.core.schedule;

import com.lifeassistant.common.auth.UserCtx;

/** 日程关联行程前校验行程归属；由出游模块实现（核心模块看不到行程表）。 */
public interface TripOwnership {

    /** 这个行程存在并且属于当前用户。 */
    boolean owns(UserCtx ctx, long tripId);
}
