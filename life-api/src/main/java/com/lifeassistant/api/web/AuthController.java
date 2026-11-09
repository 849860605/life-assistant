package com.lifeassistant.api.web;

import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.common.error.BizException;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.lifeassistant.api.account.AccountDeletion;
import com.lifeassistant.api.auth.LoginCaptcha;
import com.lifeassistant.api.auth.LoginGuard;
import com.lifeassistant.api.auth.MediaAuth;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.MediaTicket;
import com.lifeassistant.core.limit.RateLimiter;
import com.lifeassistant.core.member.CreditService;
import com.lifeassistant.core.setting.AppSettingService;
import com.lifeassistant.core.user.EmailCodeService;
import com.lifeassistant.core.user.HumanCheck;
import com.lifeassistant.core.user.PasswordCipher;
import com.lifeassistant.core.user.PasswordPolicy;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 用户登录态接口：登录（用户名或邮箱）、登出、查询当前用户、改密码，以及邮箱验证码注册、找回密码、绑定邮箱。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 同一个 IP 每天最多几个新注册的账号能拿到注册赠送积分，再多的照样能注册，只是不送 */
    static final int SIGNUP_BONUS_PER_IP_DAY = 3;

    private final UserService userService;
    private final MediaAuth mediaAuth;
    private final LoginGuard loginGuard;
    private final EmailCodeService emailCodes;
    private final AppSettingService settings;
    private final HumanCheck humanCheck;
    private final RateLimiter limiter;
    private final CreditService credits;
    private final PasswordCipher passwords;
    private final LoginCaptcha captcha;
    private final AccountDeletion accountDeletion;

    /** 登录请求体；device 可空，默认 WEB；captchaId、captchaCode 是输错几次以后要填的图形验证码。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String device,
                               String captchaId, String captchaCode) {
    }

    /** 登录结果：token 放在后续请求的 Authorization 头里。 */
    public record LoginResponse(String token, UserView user) {
    }

    /** 返回给前端的用户信息，不含密码等敏感字段；email 是绑定的邮箱，没绑为空。 */
    public record UserView(long id, String username, String nickname, String timezone, String email) {
        /** 从用户实体构造视图。 */
        static UserView of(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getTimezone(), user.getEmail());
        }
    }

    /** GET /api/v1/auth/login-key：提交密码前领的公钥和服务器时间，密码在浏览器里加密后再提交（见 PasswordCipher）。 */
    @GetMapping("/login-key")
    public ApiResponse<PasswordCipher.LoginKey> loginKey() {
        return ApiResponse.ok(passwords.loginKey());
    }

    /** GET /api/v1/auth/captcha：领一张图形验证码（登录输错几次以后要填）；同一 IP 每分钟最多 30 张。 */
    @GetMapping("/captcha")
    public ApiResponse<LoginCaptcha.Captcha> captcha(HttpServletRequest http) {
        if (!limiter.perMinute("captcha:" + http.getRemoteAddr(), 30)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "刷新太频繁，请稍后再试");
        }
        return ApiResponse.ok(captcha.issue());
    }

    /** POST /api/v1/auth/login：账号密码登录，返回 token；连续输错会暂停登录（见 LoginGuard）。device 区分设备类型（WEB / MOBILE，默认 WEB），同类设备再次登录会顶掉旧会话。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // 错误次数按账号（用户 id）记，用户名和邮箱换着输也绕不过上限；账号不存在时按输入的登录名记
        String password = passwords.open(request.password());
        Long userId = userService.findByLogin(request.username()).map(User::getId).orElse(null);
        User user = loginGuard.guard("user", request.username(), userId, http.getRemoteAddr(),
                new LoginGuard.CaptchaAnswer(request.captchaId(), request.captchaCode()),
                () -> userService.authenticate(request.username(), password));
        return ApiResponse.ok(signIn(user, request.device()));
    }

    /** 登录成功：发 token、记登录时间。 */
    private LoginResponse signIn(User user, String device) {
        StpUtil.login(user.getId(), new SaLoginParameter().setDeviceType(device == null ? "WEB" : device));
        userService.markLogin(user.getId());
        return new LoginResponse(StpUtil.getTokenValue(), UserView.of(user));
    }

    /** 注册页要知道的：是否开放注册、能不能发邮件。 */
    public record RegisterOptions(boolean open, boolean mailReady) {
    }

    /** GET /api/v1/auth/register-options：是否开放注册（不用登录）。 */
    @GetMapping("/register-options")
    public ApiResponse<RegisterOptions> registerOptions() {
        return ApiResponse.ok(new RegisterOptions(settings.registerOpen(), emailCodes.ready()));
    }

    /**
     * 要验证码：purpose 是 REGISTER 注册、RESET 找回密码、BIND 绑定邮箱（要已登录）；
     * challenge 是从 /auth/challenge 领的题目，nonce 是浏览器算出来的答案。
     */
    public record EmailCodeRequest(@NotBlank String email, @NotBlank String purpose, String challenge, String nonce) {
    }

    /** GET /api/v1/auth/challenge：领一道真人检查的题（5 分钟有效、只能用一次），算出答案后才能要验证码。 */
    @GetMapping("/challenge")
    public ApiResponse<HumanCheck.Challenge> challenge() {
        return ApiResponse.ok(humanCheck.issue());
    }

    /** 发验证码的结果：cooldown 是多少秒后才能再要一次。 */
    public record EmailCodeSent(int cooldown) {
    }

    /**
     * POST /api/v1/auth/email-code：给邮箱发 6 位验证码（10 分钟有效）。注册时邮箱已注册直接提示；
     * 找回密码时邮箱没注册也回"已发送"但不真发，不暴露这个邮箱有没有账号；绑定邮箱要已登录，邮箱被别人用了直接提示。
     */
    @PostMapping("/email-code")
    public ApiResponse<EmailCodeSent> emailCode(@Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        humanCheck.verify(request.challenge(), request.nonce());
        String email = UserService.checkEmail(request.email());
        EmailCodeService.Purpose purpose;
        try {
            purpose = EmailCodeService.Purpose.valueOf(request.purpose().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不认识的验证码用途");
        }
        boolean deliver = true;
        switch (purpose) {
            case REGISTER -> {
                requireRegisterOpen();
                // 同一个邮箱的别名（a+1@x.com、gmail 加点）也算注册过
                if (userService.findEmailOwner(email, null).isPresent()) {
                    throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
                }
            }
            case RESET -> deliver = userService.findByEmail(email).isPresent();
            case BIND -> {
                long me = StpUtil.getLoginIdAsLong();
                if (userService.findEmailOwner(email, me).isPresent()) {
                    throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
                }
            }
        }
        emailCodes.send(email, purpose, http.getRemoteAddr(), deliver);
        return ApiResponse.ok(new EmailCodeSent((int) EmailCodeService.COOLDOWN_SECONDS));
    }

    /** 邮箱注册请求体：昵称可空（默认用邮箱 @ 前面那段）；device 同登录。 */
    public record RegisterRequest(@NotBlank String email, @NotBlank String code, @NotBlank String password,
                                  String nickname, String device) {
    }

    /**
     * POST /api/v1/auth/register：邮箱 + 验证码注册，成功后直接登录（返回和登录一样）。
     * 同一个 IP 当天注册的前几个账号送注册积分，再多的照样注册成功，只是不送（防止批量注册领积分）。
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        requireRegisterOpen();
        String email = UserService.checkEmail(request.email());
        // 先查邮箱和密码，不合规时不用掉验证码
        String password = passwords.open(request.password());
        PasswordPolicy.check(password);
        if (userService.findEmailOwner(email, null).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
        }
        if (request.nickname() != null && request.nickname().trim().length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称最多 32 个字");
        }
        emailCodes.verify(email, EmailCodeService.Purpose.REGISTER, request.code());
        User user = userService.registerByEmail(email, password, request.nickname());
        if (!limiter.perDay("signup-bonus:" + http.getRemoteAddr(), SIGNUP_BONUS_PER_IP_DAY)) {
            credits.openWithoutSignupBonus(user.getId());
            log.info("IP {} 今天注册的账号太多，新账号 {} 不送注册积分", http.getRemoteAddr(), user.getId());
        }
        return ApiResponse.ok(signIn(user, request.device()));
    }

    /** 找回密码请求体。 */
    public record ResetPasswordRequest(@NotBlank String email, @NotBlank String code, @NotBlank String newPassword) {
    }

    /** POST /api/v1/auth/password/reset：邮箱验证码重置密码，成功后这个账号所有设备的登录都下线。 */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String email = UserService.checkEmail(request.email());
        String newPassword = passwords.open(request.newPassword());
        PasswordPolicy.check(newPassword);
        // 邮箱没注册时没发过验证码，这里会报"验证码已过期或还没获取"，和注册过的邮箱输错一样，不暴露有没有账号
        emailCodes.verify(email, EmailCodeService.Purpose.RESET, request.code());
        User user = userService.findByEmail(email)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "验证码已过期或还没获取，请重新获取"));
        userService.resetPassword(user.getId(), newPassword);
        StpUtil.logout(user.getId());
        return ApiResponse.ok();
    }

    /** 绑定邮箱请求体。 */
    public record BindEmailRequest(@NotBlank String email, @NotBlank String code) {
    }

    /** POST /api/v1/auth/email/bind：已登录的用户绑定（或换）邮箱，绑了能用邮箱登录和找回密码。 */
    @PostMapping("/email/bind")
    public ApiResponse<UserView> bindEmail(@Valid @RequestBody BindEmailRequest request) {
        long me = StpUtil.getLoginIdAsLong();
        String email = UserService.checkEmail(request.email());
        if (userService.findEmailOwner(email, me).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
        }
        emailCodes.verify(email, EmailCodeService.Purpose.BIND, request.code());
        return ApiResponse.ok(UserView.of(userService.bindEmail(me, email)));
    }

    /** 注册关了时报 FORBIDDEN。 */
    private void requireRegisterOpen() {
        if (!settings.registerOpen()) {
            throw new BizException(ErrorCode.FORBIDDEN, "暂未开放注册");
        }
    }

    /** POST /api/v1/auth/logout：注销当前 token。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.ok();
    }

    /** GET /api/v1/auth/me：当前登录用户的信息。 */
    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(UserView.of(userService.get(StpUtil.getLoginIdAsLong())));
    }

    /** 注销账号请求体：当前密码（加密后的）。 */
    public record DeleteAccountRequest(@NotBlank String password) {
    }

    /**
     * POST /api/v1/auth/account/delete：注销自己的账号，要当前密码（输错和登录一样计次、会暂停）。
     * 对话、记忆、行程、日程、收藏、文件全部删除，账号里的个人信息清空，所有设备下线；付款订单和积分流水按法律保留。
     */
    @PostMapping("/account/delete")
    public ApiResponse<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest request, HttpServletRequest http) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = userService.get(userId);
        String password = passwords.open(request.password());
        try {
            loginGuard.guard("user-password", user.getUsername(), userId, http.getRemoteAddr(),
                    () -> userService.authenticate(user.getUsername(), password));
        } catch (BizException e) {
            // 密码错用 400 报出去，401 会让前端以为登录过期、直接退出
            if (e.errorCode() == ErrorCode.UNAUTHORIZED) {
                throw new BizException(ErrorCode.BAD_REQUEST, "密码不对");
            }
            throw e;
        }
        accountDeletion.delete(userId);
        return ApiResponse.ok();
    }

    /** 改密码请求体：原密码和新密码。 */
    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    /**
     * POST /api/v1/auth/password：改自己的密码（要原密码，原密码连续输错和登录一样会暂停）；
     * 改完其他设备上的登录下线，当前这个保留。
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = userService.get(userId);
        String oldPassword = passwords.open(request.oldPassword());
        String newPassword = passwords.open(request.newPassword());
        try {
            loginGuard.guard("user-password", user.getUsername(), userId, http.getRemoteAddr(), () -> {
                userService.changeOwnPassword(userId, oldPassword, newPassword);
                return true;
            });
        } catch (BizException e) {
            // 原密码错用 400 报出去，401 会让前端以为登录过期、直接退出
            if (e.errorCode() == ErrorCode.UNAUTHORIZED) {
                throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
            }
            throw e;
        }
        String current = StpUtil.getTokenValue();
        StpUtil.getTokenValueListByLoginId(userId).stream()
                .filter(t -> !t.equals(current)).forEach(StpUtil::logoutByTokenValue);
        return ApiResponse.ok();
    }

    /**
     * GET /api/v1/auth/media-ticket：地图图片、PDF 下载链接用的短期票据（4 小时），代替把登录 token 放进链接。
     * 改密码、重置密码、停用账号后之前的票据作废。
     */
    @GetMapping("/media-ticket")
    public ApiResponse<MediaTicket.Issued> mediaTicket() {
        return ApiResponse.ok(mediaAuth.issue(StpUtil.getLoginIdAsLong()));
    }
}
