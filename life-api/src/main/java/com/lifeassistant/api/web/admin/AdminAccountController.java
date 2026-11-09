package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.api.auth.StpAdminUtil;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.AdminUserService;
import com.lifeassistant.core.user.PasswordCipher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员账号管理（只有超级管理员能用）：列表、新建、重置密码、启用停用。
 * 重置密码和停用会让这个管理员已登录的地方全部下线。
 */
@RestController
@RequestMapping("/admin/api/v1/admins")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminUserService adminUserService;
    private final AdminContext adminContext;
    private final PasswordCipher passwords;

    /** 新建管理员：role 是 SUPER 或 ADMIN。 */
    public record CreateAdminRequest(@NotBlank String username, @NotBlank String password, @NotBlank String role,
                                     String nickname) {
    }

    /** 重置密码的请求体。 */
    public record PasswordRequest(@NotBlank String password) {
    }

    /** 启用停用的请求体。 */
    public record StatusRequest(boolean enabled) {
    }

    /** GET /admin/api/v1/admins：全部管理员。 */
    @GetMapping
    public ApiResponse<List<AdminController.AdminView>> list() {
        adminContext.requireSuper();
        return ApiResponse.ok(adminUserService.list().stream().map(AdminController.AdminView::of).toList());
    }

    /** POST /admin/api/v1/admins：新建管理员。 */
    @PostMapping
    public ApiResponse<AdminController.AdminView> create(@Valid @RequestBody CreateAdminRequest request) {
        AdminUser me = adminContext.requireSuper();
        AdminUser created = adminUserService.create(request.username(), passwords.open(request.password()), request.role(),
                request.nickname());
        adminContext.log(me.getId(), "CREATE_ADMIN", created.getUsername() + "（" + created.getRole() + "）");
        return ApiResponse.ok(AdminController.AdminView.of(adminUserService.get(created.getId())));
    }

    /** POST /admin/api/v1/admins/{id}/password：重置别的管理员的密码；改自己的密码走 /auth/password。 */
    @PostMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable long id, @Valid @RequestBody PasswordRequest request) {
        AdminUser me = adminContext.requireSuper();
        if (me.getId() == id) {
            throw new BizException(ErrorCode.BAD_REQUEST, "改自己的密码请到\"修改密码\"页，要输原密码");
        }
        AdminUser target = adminUserService.get(id);
        adminUserService.resetPassword(id, passwords.open(request.password()));
        StpAdminUtil.STP.logout(id);
        adminContext.log(me.getId(), "RESET_ADMIN_PASSWORD", target.getUsername());
        return ApiResponse.ok();
    }

    /** POST /admin/api/v1/admins/{id}/status：启用或停用；不能停用自己和最后一个超级管理员，停用后立即下线。 */
    @PostMapping("/{id}/status")
    public ApiResponse<AdminController.AdminView> setStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        AdminUser me = adminContext.requireSuper();
        AdminUser target = adminUserService.setEnabled(me.getId(), id, request.enabled());
        if (!request.enabled()) {
            StpAdminUtil.STP.logout(id);
        }
        adminContext.log(me.getId(), request.enabled() ? "ENABLE_ADMIN" : "DISABLE_ADMIN", target.getUsername());
        return ApiResponse.ok(AdminController.AdminView.of(adminUserService.get(id)));
    }
}
