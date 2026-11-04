package com.lifeassistant.api.web;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.lifeassistant.api.auth.LoginGuard;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.MediaTicket;
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

/**
 * 用户登录态接口：登录、登出、查询当前用户。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final MediaTicket mediaTicket;
    private final LoginGuard loginGuard;

    /** 登录请求体；device 可空，默认 WEB。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String device) {
    }

    /** 登录结果：token 放在后续请求的 Authorization 头里。 */
    public record LoginResponse(String token, UserView user) {
    }

    /** 返回给前端的用户信息，不含密码等敏感字段。 */
    public record UserView(long id, String username, String nickname, String timezone) {
        /** 从用户实体构造视图。 */
        static UserView of(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getTimezone());
        }
    }

    /** POST /api/v1/auth/login：账号密码登录，返回 token；连续输错会暂停登录（见 LoginGuard）。device 区分设备类型（WEB / MOBILE，默认 WEB），同类设备再次登录会顶掉旧会话。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        User user = loginGuard.guard("user", request.username(), http.getRemoteAddr(),
                () -> userService.authenticate(request.username(), request.password()));
        StpUtil.login(user.getId(), new SaLoginParameter()
                .setDeviceType(request.device() == null ? "WEB" : request.device()));
        userService.markLogin(user.getId());
        return ApiResponse.ok(new LoginResponse(StpUtil.getTokenValue(), UserView.of(user)));
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

    /** GET /api/v1/auth/media-ticket：地图图片、PDF 下载链接用的短期票据（12 小时），代替把登录 token 放进链接。 */
    @GetMapping("/media-ticket")
    public ApiResponse<MediaTicket.Issued> mediaTicket() {
        return ApiResponse.ok(mediaTicket.issue(StpUtil.getLoginIdAsLong()));
    }
}
