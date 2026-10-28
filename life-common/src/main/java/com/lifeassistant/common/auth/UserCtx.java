package com.lifeassistant.common.auth;

import java.io.Serializable;
import java.util.Set;

/**
 * 当前用户身份。业务代码只认它，不关心身份来自 Sa-Token 还是以后的 OAuth2。
 * <p>
 * AI 链路（流式输出、Graph 节点、工具调用）运行在其他线程，拿不到登录态，
 * 所以在 Controller 入口构造一次，之后通过 Graph 状态和 ToolContext 显式传递。
 */
public record UserCtx(long userId, long spaceId, long tenantId, Set<String> perms) implements Serializable {

    /** 在 Graph 状态和 ToolContext 里存放 UserCtx 用的 key。 */
    public static final String KEY = "userCtx";

    /** 只有用户 id 的身份：空间、租户为 0，无额外权限。 */
    public static UserCtx of(long userId) {
        return new UserCtx(userId, 0L, 0L, Set.of());
    }
}
