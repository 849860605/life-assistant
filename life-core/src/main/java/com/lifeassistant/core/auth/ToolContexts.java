package com.lifeassistant.core.auth;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/** 工具方法从 ToolContext 里取身份，不调用 Sa-Token（工具运行在非请求线程）。 */
public final class ToolContexts {

    public static final String CONVERSATION_ID = "conversationId";

    private ToolContexts() {
    }

    /** 构造传给模型调用的 ToolContext 内容：用户身份 + 会话 ID。 */
    public static Map<String, Object> of(UserCtx ctx, String conversationId) {
        return Map.of(UserCtx.KEY, ctx, CONVERSATION_ID, conversationId);
    }

    /** 从 ToolContext 取用户身份，取不到抛 UNAUTHORIZED。 */
    public static UserCtx user(ToolContext toolContext) {
        Object ctx = toolContext == null ? null : toolContext.getContext().get(UserCtx.KEY);
        if (ctx instanceof UserCtx userCtx) {
            return userCtx;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED, "工具调用缺少用户身份");
    }
}
