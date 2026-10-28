package com.lifeassistant.api.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.auth.AuthFacade;
import org.springframework.stereotype.Component;

/**
 * 用 Sa-Token 实现的身份获取。只在请求线程里用；AI 链路里不要调用 StpUtil。
 * 共享空间（spaceId）S2 启用，现在固定为 0。
 */
@Component
public class SaTokenAuthFacade implements AuthFacade {

    /** 从当前请求的登录态构造 UserCtx；未登录时抛 NotLoginException。 */
    @Override
    public UserCtx current() {
        return UserCtx.of(StpUtil.getLoginIdAsLong());
    }
}
