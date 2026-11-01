package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.LoginGuard;
import com.lifeassistant.api.auth.StpAdminUtil;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.audit.AdminOpLogger;
import com.lifeassistant.core.audit.LlmUsageMapper;
import com.lifeassistant.core.audit.ToolCallLogMapper;
import com.lifeassistant.core.eval.EvalRun;
import com.lifeassistant.core.eval.EvalService;
import com.lifeassistant.core.schedule.ReminderDispatcher;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口。S0 没有后台页面，通过 SpringDoc 的接口调试页面（/swagger-ui.html）调用。
 */
@RestController
@RequestMapping("/admin/api/v1")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService adminUserService;
    private final EvalService evalService;
    private final ReminderDispatcher reminderDispatcher;
    private final LlmUsageMapper usageMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final AdminOpLogger opLogger;
    private final LoginGuard loginGuard;

    /** 管理员登录请求体。 */
    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** POST /admin/api/v1/auth/login：管理员登录，返回管理端 token（与用户 token 独立）；连续输错会暂停登录。 */
    @PostMapping("/auth/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody AdminLoginRequest request,
                                                  HttpServletRequest http) {
        AdminUser admin = loginGuard.guard("admin", request.username(), http.getRemoteAddr(),
                () -> adminUserService.authenticate(request.username(), request.password()));
        StpAdminUtil.STP.login(admin.getId());
        return ApiResponse.ok(Map.of("token", StpAdminUtil.STP.getTokenValue()));
    }

    /** POST /admin/api/v1/eval/routing：运行路由评测并记操作日志（会调用模型，产生费用）。 */
    @PostMapping("/eval/routing")
    public ApiResponse<EvalRun> runRoutingEval() {
        opLogger.log(StpAdminUtil.STP.getLoginIdAsLong(), "EVAL_ROUTING", null);
        return ApiResponse.ok(evalService.runRouting("ADMIN"));
    }

    /** POST /admin/api/v1/reminders/{id}/retry：重置提醒状态和重试次数后立即重发，并记操作日志。 */
    @PostMapping("/reminders/{id}/retry")
    public ApiResponse<Void> retryReminder(@PathVariable long id) {
        opLogger.log(StpAdminUtil.STP.getLoginIdAsLong(), "RETRY_REMINDER", String.valueOf(id));
        reminderDispatcher.retry(id);
        return ApiResponse.ok();
    }

    /** GET /admin/api/v1/calls/stats：最近 days 天（限制在 1~90）各工具和各平台的调用次数、失败数、平均和 P95 耗时。 */
    @GetMapping("/calls/stats")
    public ApiResponse<List<Map<String, Object>>> callStats(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(toolCallLogMapper.stats(Math.min(Math.max(days, 1), 90)));
    }

    /** GET /admin/api/v1/usage/daily：最近 days 天（限制在 1~90）每天每个模型的 Token 用量和费用（元）。 */
    @GetMapping("/usage/daily")
    public ApiResponse<List<Map<String, Object>>> dailyUsage(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(usageMapper.dailySummary(Math.min(Math.max(days, 1), 90)));
    }
}
