package com.lifeassistant.api.auth;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.AdminOpLogger;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 管理端接口的公共部分：取当前管理员（被停用了就踢下线）、要求超级管理员、带 IP 记操作日志。
 * 登录校验在 SaTokenConfig 里已经做了，这里只管账号状态和角色。
 */
@Component
@RequiredArgsConstructor
public class AdminContext {

    private final AdminUserService adminUserService;
    private final AdminOpLogger opLogger;
    private final HttpServletRequest request;

    /** 当前登录的管理员；账号已停用或已删除时注销这个 token 并报 401。 */
    public AdminUser current() {
        long id = StpAdminUtil.STP.getLoginIdAsLong();
        AdminUser admin;
        try {
            admin = adminUserService.get(id);
        } catch (BizException e) {
            StpAdminUtil.STP.logout(id);
            throw new BizException(ErrorCode.UNAUTHORIZED, "请重新登录");
        }
        if (admin.getStatus() == null || admin.getStatus() != 1) {
            StpAdminUtil.STP.logout(id);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已停用");
        }
        return admin;
    }

    /** 当前管理员必须是超级管理员，否则报 403。 */
    public AdminUser requireSuper() {
        AdminUser admin = current();
        if (!AdminUserService.SUPER.equals(admin.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有超级管理员能做这个操作");
        }
        return admin;
    }

    /** 记一条当前管理员的操作日志（带 IP）。 */
    public void log(long adminId, String action, String target) {
        opLogger.log(adminId, action, target, request.getRemoteAddr());
    }

    /** 当前请求的客户端 IP（经 nginx 转发时取转发头）。 */
    public String ip() {
        return request.getRemoteAddr();
    }
}
