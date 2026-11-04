package com.lifeassistant.api.web.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.audit.AdminStatsService;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserService;
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

/**
 * App 用户管理（管理员都能用）：分页列表、新建、看概要、重置密码、启用停用。
 * 只给计数和时间，不提供对话正文、行程内容（隐私）。重置密码和停用会让这个用户所有设备下线。
 */
@RestController
@RequestMapping("/admin/api/v1/users")
@RequiredArgsConstructor
public class AdminAppUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;
    private final AdminStatsService statsService;
    private final AdminContext adminContext;

    /** 新建用户的请求体。 */
    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, String nickname) {
    }

    /** 重置密码的请求体。 */
    public record PasswordRequest(@NotBlank String password) {
    }

    /** 启用停用的请求体。 */
    public record StatusRequest(boolean enabled) {
    }

    /** 一个用户的基本信息加概要统计。 */
    public record UserDetail(long id, String username, String nickname, String timezone, boolean enabled,
                             OffsetDateTime createdAt, OffsetDateTime lastLoginAt,
                             AdminStatsService.UserSummary summary) {
    }

    /** GET /admin/api/v1/users?keyword=&page=1&size=20：用户列表，按用户名或昵称搜。 */
    @GetMapping
    public ApiResponse<AdminStatsService.Page<AdminStatsService.UserRow>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminContext.current();
        return ApiResponse.ok(statsService.users(keyword, Math.max(page, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    /** POST /admin/api/v1/users：新建用户（用户名、初始密码、昵称）。 */
    @PostMapping
    public ApiResponse<UserDetail> create(@Valid @RequestBody CreateUserRequest request) {
        AdminUser me = adminContext.current();
        User user = userService.createByAdmin(request.username(), request.password(), request.nickname());
        adminContext.log(me.getId(), "CREATE_USER", user.getUsername());
        return ApiResponse.ok(detail(user.getId()));
    }

    /** GET /admin/api/v1/users/{id}：用户基本信息和概要统计。 */
    @GetMapping("/{id}")
    public ApiResponse<UserDetail> get(@PathVariable long id) {
        adminContext.current();
        return ApiResponse.ok(detail(id));
    }

    /** POST /admin/api/v1/users/{id}/password：重置用户密码，所有设备下线。 */
    @PostMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable long id, @Valid @RequestBody PasswordRequest request) {
        AdminUser me = adminContext.current();
        User user = userService.get(id);
        userService.resetPassword(id, request.password());
        StpUtil.logout(id);
        adminContext.log(me.getId(), "RESET_USER_PASSWORD", user.getUsername());
        return ApiResponse.ok();
    }

    /** POST /admin/api/v1/users/{id}/status：启用或停用；停用后不能登录，已登录的设备立即下线。 */
    @PostMapping("/{id}/status")
    public ApiResponse<UserDetail> setStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        AdminUser me = adminContext.current();
        User user = userService.setEnabled(id, request.enabled());
        if (!request.enabled()) {
            StpUtil.logout(id);
        }
        adminContext.log(me.getId(), request.enabled() ? "ENABLE_USER" : "DISABLE_USER", user.getUsername());
        return ApiResponse.ok(detail(id));
    }

    private UserDetail detail(long id) {
        User u = userService.get(id);
        return new UserDetail(u.getId(), u.getUsername(), u.getNickname(), u.getTimezone(),
                u.getStatus() != null && u.getStatus() == 1, u.getCreatedAt(), u.getLastLoginAt(),
                statsService.userSummary(id));
    }
}
