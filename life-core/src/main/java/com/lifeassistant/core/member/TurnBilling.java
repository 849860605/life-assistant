package com.lifeassistant.core.member;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.conversation.TurnFlags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 对话里按"事情"扣积分（工具里调用）：同一件事一轮只扣一次。
 * 规划新行程扣规划的价，改已有行程扣修改的价（同一轮已经扣过这个行程的规划就不再扣修改）；
 * 完整比价每轮第一次扣。不在对话里调用（比如页面直接查）时不扣，页面那边自己扣。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnBilling {

    private final TurnFlags turnFlags;
    private final CreditService credits;

    /** AI 保存了行程（已经做完了才扣，余额不够允许透支）：created 为 true 是新建。 */
    public void tripSaved(long tripId, boolean created) {
        CallRecorder.Scope scope = CallRecorder.current();
        if (scope == null || scope.userId() == null || scope.conversationId() == null) {
            return;
        }
        String planKey = "plan:" + tripId;
        if (created) {
            if (turnFlags.once(planKey)) {
                charge(scope, CreditService.PLAN, "trip:" + tripId);
            }
        } else if (!turnFlags.done(planKey) && turnFlags.once("edit:" + tripId)) {
            charge(scope, CreditService.EDIT, "trip:" + tripId);
        }
    }

    /**
     * 对话里要做一次完整比价：这一轮第一次比价时扣积分，余额不够返回要转告用户的话（这时不去查），够了或不用扣返回 null。
     */
    public String compare() {
        CallRecorder.Scope scope = CallRecorder.current();
        if (scope == null || scope.userId() == null || scope.conversationId() == null || !turnFlags.once("compare")) {
            return null;
        }
        try {
            credits.spend(scope.userId(), CreditService.COMPARE, scope.conversationId(), false);
            return null;
        } catch (BizException e) {
            return e.getMessage();
        }
    }

    private void charge(CallRecorder.Scope scope, String feature, String ref) {
        try {
            credits.spend(scope.userId(), feature, ref, true);
        } catch (RuntimeException e) {
            // 扣费失败不影响已经保存好的行程
            log.warn("扣积分失败 userId={} feature={}：{}", scope.userId(), feature, e.getMessage());
        }
    }
}
