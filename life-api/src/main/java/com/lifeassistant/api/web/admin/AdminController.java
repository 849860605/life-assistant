package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.api.auth.LoginCaptcha;
import com.lifeassistant.api.auth.LoginGuard;
import com.lifeassistant.api.auth.StpAdminUtil;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.LlmUsageMapper;
import com.lifeassistant.core.audit.ToolCallLogMapper;
import com.lifeassistant.core.eval.EvalRun;
import com.lifeassistant.core.eval.EvalService;
import com.lifeassistant.core.schedule.ReminderDispatcher;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.AdminUserService;
import com.lifeassistant.core.limit.RateLimiter;
import com.lifeassistant.core.user.PasswordCipher;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端接口：管理员登录、退出、当前账号、改自己密码，以及评测、提醒重发、调用统计、用量。
 * 管理员、用户、总览和操作日志在同目录的其他 Controller 里。
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
    private final LoginGuard loginGuard;
    private final AdminContext adminContext;
    private final PasswordCipher passwords;
    private final LoginCaptcha captcha;
    private final RateLimiter limiter;

    /** 管理员登录请求体。 */
    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password, String captchaId, String captchaCode) {
    }

    /** 改自己密码的请求体。 */
    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    /** 返回给前端的管理员信息，不含密码。 */
    public record AdminView(long id, String username, String nickname, String role, boolean enabled,
                            OffsetDateTime lastLoginAt, OffsetDateTime createdAt) {

        /** 从实体构造视图。 */
        public static AdminView of(AdminUser a) {
            return new AdminView(a.getId(), a.getUsername(), a.getNickname(), a.getRole(),
                    a.getStatus() != null && a.getStatus() == 1, a.getLastLoginAt(), a.getCreatedAt());
        }
    }

    /** 登录结果：token 放在后续请求的 Authorization 头里（Bearer），和用户端 token 互不通用。 */
    public record AdminLoginResponse(String token, AdminView admin) {
    }

    /** GET /admin/api/v1/auth/login-key：提交密码前领的公钥和服务器时间，密码在浏览器里加密后再提交（见 PasswordCipher）。 */
    @GetMapping("/auth/login-key")
    public ApiResponse<PasswordCipher.LoginKey> loginKey() {
        return ApiResponse.ok(passwords.loginKey());
    }

    /** GET /admin/api/v1/auth/captcha：领一张图形验证码（登录输错几次以后要填）；同一 IP 每分钟最多 30 张。 */
    @GetMapping("/auth/captcha")
    public ApiResponse<LoginCaptcha.Captcha> captcha(HttpServletRequest http) {
        if (!limiter.perMinute("captcha:" + http.getRemoteAddr(), 30)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "刷新太频繁，请稍后再试");
        }
        return ApiResponse.ok(captcha.issue());
    }

    /** POST /admin/api/v1/auth/login：管理员登录；连续输错会暂停登录（见 LoginGuard）。 */
    @PostMapping("/auth/login")
    public ApiResponse<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                 HttpServletRequest http) {
        String password = passwords.open(request.password());
        AdminUser admin = loginGuard.guard("admin", request.username(), null, http.getRemoteAddr(),
                new LoginGuard.CaptchaAnswer(request.captchaId(), request.captchaCode()),
                () -> adminUserService.authenticate(request.username(), password));
        StpAdminUtil.STP.login(admin.getId());
        adminContext.log(admin.getId(), "LOGIN", null);
        return ApiResponse.ok(new AdminLoginResponse(StpAdminUtil.STP.getTokenValue(),
                AdminView.of(adminUserService.get(admin.getId()))));
    }

    /** POST /admin/api/v1/auth/logout：注销当前管理端 token。 */
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout() {
        StpAdminUtil.STP.logout();
        return ApiResponse.ok();
    }

    /** GET /admin/api/v1/auth/me：当前管理员。 */
    @GetMapping("/auth/me")
    public ApiResponse<AdminView> me() {
        return ApiResponse.ok(AdminView.of(adminContext.current()));
    }

    /** POST /admin/api/v1/auth/password：改自己的密码（要原密码）；改完其他地方登录的会话下线，当前这个保留。 */
    @PostMapping("/auth/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AdminUser admin = adminContext.current();
        adminUserService.changeOwnPassword(admin.getId(), passwords.open(request.oldPassword()),
                passwords.open(request.newPassword()));
        String current = StpAdminUtil.STP.getTokenValue();
        StpAdminUtil.STP.getTokenValueListByLoginId(admin.getId()).stream()
                .filter(t -> !t.equals(current)).forEach(StpAdminUtil.STP::logoutByTokenValue);
        adminContext.log(admin.getId(), "CHANGE_OWN_PASSWORD", null);
        return ApiResponse.ok();
    }

    /** POST /admin/api/v1/eval/routing：运行路由评测并记操作日志（会调用模型，产生费用）。 */
    @PostMapping("/eval/routing")
    public ApiResponse<EvalRun> runRoutingEval() {
        adminContext.log(adminContext.current().getId(), "EVAL_ROUTING", null);
        return ApiResponse.ok(evalService.runRouting("ADMIN"));
    }

    /** POST /admin/api/v1/reminders/{id}/retry：重置提醒状态和重试次数后立即重发，并记操作日志。 */
    @PostMapping("/reminders/{id}/retry")
    public ApiResponse<Void> retryReminder(@PathVariable long id) {
        adminContext.log(adminContext.current().getId(), "RETRY_REMINDER", String.valueOf(id));
        reminderDispatcher.retry(id);
        return ApiResponse.ok();
    }

    /** GET /admin/api/v1/calls/stats：最近 days 天（限制在 1~90）各工具和各平台的调用次数、失败数、平均和 P95 耗时。 */
    @GetMapping("/calls/stats")
    public ApiResponse<List<Map<String, Object>>> callStats(@RequestParam(defaultValue = "7") int days) {
        adminContext.current();
        return ApiResponse.ok(toolCallLogMapper.stats(Math.min(Math.max(days, 1), 90)));
    }

    /** GET /admin/api/v1/usage/daily：最近 days 天（限制在 1~90）每天每个模型的 Token 用量和费用（元）。 */
    @GetMapping("/usage/daily")
    public ApiResponse<List<Map<String, Object>>> dailyUsage(@RequestParam(defaultValue = "7") int days) {
        adminContext.current();
        return ApiResponse.ok(usageMapper.dailySummary(Math.min(Math.max(days, 1), 90)));
    }
}
