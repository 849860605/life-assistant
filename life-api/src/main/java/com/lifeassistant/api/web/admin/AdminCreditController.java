package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.member.CreditService;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理后台的积分：价格表和赠送规则、积分包，本月卖出和各项用量，某个用户的积分和流水、加减积分。
 * 改价格、加减积分只有超级管理员能做；加减积分要写原因。都记操作日志。
 */
@RestController
@RequestMapping("/admin/api/v1/credits")
@RequiredArgsConstructor
public class AdminCreditController {

    private final CreditService credits;
    private final UserMapper userMapper;
    private final AdminContext adminContext;

    /** 价格表和中文名。 */
    public record PlanView(CreditService.Plan plan, Map<String, String> features, String packsText) {
    }

    /** 改价格的请求：各项价格、注册送、每天送、积分包（"元:积分"逗号隔开）。 */
    public record PlanRequest(Map<String, Integer> prices, int signup, int daily, String packs) {
    }

    /** 某个用户的积分和最近流水。 */
    public record UserCredits(CreditService.View view, List<CreditService.Entry> ledger) {
    }

    /** 加减积分的请求：正数加、负数扣，note 写原因。 */
    public record AdjustRequest(int delta, String note) {
    }

    /** GET /admin/api/v1/credits/plan：现在的价格表。 */
    @GetMapping("/plan")
    public ApiResponse<PlanView> plan() {
        adminContext.current();
        return ApiResponse.ok(planView());
    }

    /** PUT /admin/api/v1/credits/plan：改价格、赠送规则和积分包，马上生效（已买的积分不变）。 */
    @PutMapping("/plan")
    public ApiResponse<PlanView> setPlan(@RequestBody PlanRequest request) {
        AdminUser me = adminContext.requireSuper();
        CreditService.Plan p = credits.setPlan(request.prices() == null ? Map.of() : request.prices(), request.signup(),
                request.daily(), request.packs());
        adminContext.log(me.getId(), "SET_CREDIT_PLAN", "价格 " + p.prices() + "，注册送 " + p.signup() + "，每天送 "
                + p.daily() + "，积分包 " + request.packs());
        return ApiResponse.ok(planView());
    }

    /** GET /admin/api/v1/credits/stats：本月卖出多少积分、各项用了多少。 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        adminContext.current();
        return ApiResponse.ok(credits.monthStats());
    }

    /** GET /admin/api/v1/credits/users/{userId}：某个用户的积分和最近 100 条流水。 */
    @GetMapping("/users/{userId}")
    public ApiResponse<UserCredits> user(@PathVariable long userId) {
        adminContext.current();
        requireUser(userId);
        return ApiResponse.ok(new UserCredits(credits.view(userId), credits.ledger(userId, 100)));
    }

    /** POST /admin/api/v1/credits/users/{userId}/adjust：给用户加减积分（送朋友、补偿、扣回），要写原因；只有超级管理员能做。 */
    @PostMapping("/users/{userId}/adjust")
    public ApiResponse<UserCredits> adjust(@PathVariable long userId, @RequestBody AdjustRequest request) {
        AdminUser me = adminContext.requireSuper();
        requireUser(userId);
        credits.adjustByAdmin(userId, request.delta(), me.getId(), request.note());
        adminContext.log(me.getId(), "ADJUST_CREDIT", "用户 " + userId + (request.delta() > 0 ? " +" : " ") + request.delta()
                + " 积分（" + request.note().trim() + "）");
        return ApiResponse.ok(new UserCredits(credits.view(userId), credits.ledger(userId, 100)));
    }

    private PlanView planView() {
        CreditService.Plan p = credits.plan();
        String packs = String.join(",", p.packs().stream()
                .map(k -> k.yuan().stripTrailingZeros().toPlainString() + ":" + k.credits()).toList());
        return new PlanView(p, CreditService.FEATURES, packs);
    }

    private void requireUser(long userId) {
        if (userMapper.selectById(userId) == null) {
            throw BizException.notFound("用户");
        }
    }
}
