package com.lifeassistant.core.executor;

import com.lifeassistant.common.auth.UserCtx;

import java.util.Map;

/**
 * 执行器：官方接口、半自动跳转、GUI Agent 都实现它。
 * 外卖、跑腿等延后的能力以及平台开放后的预留能力，以后都以新实现的方式接入，
 * 执行顺序由配置 life.executors.* 决定（见 docs/blueprint.md 第 4 节）。
 * S0 还没有实现类。
 */
public interface ActionExecutor {

    /** 执行器唯一标识，配置里按它排序。 */
    String id();

    /** 所属执行层级。 */
    ExecTier tier();

    /** 能否为该用户处理这个动作，用于执行路由挑选执行器。 */
    boolean supports(ActionIntent intent, UserCtx ctx);

    /** 生成预览给用户确认，此时不产生任何实际操作。 */
    ActionPreview preview(ActionIntent intent, UserCtx ctx);

    /** 按用户确认过的预览执行。 */
    ActionResult execute(ActionPreview confirmed, UserCtx ctx);

    /** 要做的动作及参数，由技能生成。 */
    record ActionIntent(String action, Map<String, Object> params) {
    }

    /** 执行前展示给用户的预览，确认后原样传回 execute。 */
    record ActionPreview(String executorId, String action, String summary, Map<String, Object> details) {
    }

    /** 执行结果；半自动执行时 jumpUrl 是让用户点击完成的链接。 */
    record ActionResult(boolean success, String message, String jumpUrl) {
    }
}
