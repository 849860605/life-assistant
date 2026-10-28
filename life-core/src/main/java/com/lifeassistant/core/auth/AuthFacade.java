package com.lifeassistant.core.auth;

import com.lifeassistant.common.auth.UserCtx;

/**
 * 业务代码获取当前用户的唯一入口。只能在请求线程里调用；
 * AI 链路里要用 Graph 状态或 ToolContext 中的身份。
 */
public interface AuthFacade {

    /** 当前请求的用户身份，未登录时由实现抛异常。 */
    UserCtx current();
}
