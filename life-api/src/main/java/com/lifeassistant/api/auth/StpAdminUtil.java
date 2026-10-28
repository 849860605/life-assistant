package com.lifeassistant.api.auth;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理员的独立账号体系（loginType = admin），token 和会话与用户登录互不影响。
 */
public final class StpAdminUtil {

    public static final String TYPE = "admin";
    public static final StpLogic STP = new StpLogic(TYPE);

    private StpAdminUtil() {
    }
}
