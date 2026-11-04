package com.lifeassistant.api.web;

import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.common.error.BizException;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.lifeassistant.api.auth.LoginGuard;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.MediaTicket;
import com.lifeassistant.core.setting.AppSettingService;
import com.lifeassistant.core.user.EmailCodeService;
import com.lifeassistant.core.user.PasswordPolicy;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 用户登录态接口：登录（用户名或邮箱）、登出、查询当前用户、改密码，以及邮箱验证码注册、找回密码、绑定邮箱。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final MediaTicket mediaTicket;
    private final LoginGuard loginGuard;
    private final EmailCodeService emailCodes;
    private final AppSettingService settings;

    /** 登录请求体；device 可空，默认 WEB。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String device) {
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

    /** POST /api/v1/auth/login：账号密码登录，返回 token；连续输错会暂停登录（见 LoginGuard）。device 区分设备类型（WEB / MOBILE，默认 WEB），同类设备再次登录会顶掉旧会话。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        User user = loginGuard.guard("user", request.username(), http.getRemoteAddr(),
                () -> userService.authenticate(request.username(), request.password()));
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

    /** 要验证码：purpose 是 REGISTER 注册、RESET 找回密码、BIND 绑定邮箱（要已登录）。 */
    public record EmailCodeRequest(@NotBlank String email, @NotBlank String purpose) {
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
                if (userService.findByEmail(email).isPresent()) {
                    throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
                }
            }
            case RESET -> deliver = userService.findByEmail(email).isPresent();
            case BIND -> {
                long me = StpUtil.getLoginIdAsLong();
                userService.findByEmail(email).filter(u -> u.getId() != me).ifPresent(u -> {
                    throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
                });
            }
        }
        emailCodes.send(email, purpose, http.getRemoteAddr(), deliver);
        return ApiResponse.ok(new EmailCodeSent((int) EmailCodeService.COOLDOWN_SECONDS));
    }

    /** 邮箱注册请求体：昵称可空（默认用邮箱 @ 前面那段）；device 同登录。 */
    public record RegisterRequest(@NotBlank String email, @NotBlank String code, @NotBlank String password,
                                  String nickname, String device) {
    }

    /** POST /api/v1/auth/register：邮箱 + 验证码注册，成功后直接登录（返回和登录一样）。 */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        requireRegisterOpen();
        String email = UserService.checkEmail(request.email());
        // 先查邮箱和密码，不合规时不用掉验证码
        PasswordPolicy.check(request.password());
        if (userService.findByEmail(email).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
        }
        if (request.nickname() != null && request.nickname().trim().length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称最多 32 个字");
        }
        emailCodes.verify(email, EmailCodeService.Purpose.REGISTER, request.code());
        User user = userService.registerByEmail(email, request.password(), request.nickname());
        return ApiResponse.ok(signIn(user, request.device()));
    }

    /** 找回密码请求体。 */
    public record ResetPasswordRequest(@NotBlank String email, @NotBlank String code, @NotBlank String newPassword) {
    }

    /** POST /api/v1/auth/password/reset：邮箱验证码重置密码，成功后这个账号所有设备的登录都下线。 */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String email = UserService.checkEmail(request.email());
        PasswordPolicy.check(request.newPassword());
        // 邮箱没注册时没发过验证码，这里会报"验证码已过期或还没获取"，和注册过的邮箱输错一样，不暴露有没有账号
        emailCodes.verify(email, EmailCodeService.Purpose.RESET, request.code());
        User user = userService.findByEmail(email)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "验证码已过期或还没获取，请重新获取"));
        userService.resetPassword(user.getId(), request.newPassword());
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
        userService.findByEmail(email).filter(u -> u.getId() != me).ifPresent(u -> {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
        });
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
        try {
            loginGuard.guard("user-password", user.getUsername(), http.getRemoteAddr(), () -> {
                userService.changeOwnPassword(userId, request.oldPassword(), request.newPassword());
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

    /** GET /api/v1/auth/media-ticket：地图图片、PDF 下载链接用的短期票据（12 小时），代替把登录 token 放进链接。 */
    @GetMapping("/media-ticket")
    public ApiResponse<MediaTicket.Issued> mediaTicket() {
        return ApiResponse.ok(mediaTicket.issue(StpUtil.getLoginIdAsLong()));
    }
}
