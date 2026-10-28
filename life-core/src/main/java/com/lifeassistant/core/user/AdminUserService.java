package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 管理员账号的创建和登录校验。 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;

    /** 新建启用状态的管理员，密码加密存储；不检查用户名是否重复。 */
    public AdminUser create(String username, String rawPassword) {
        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setStatus(1);
        adminUserMapper.insert(admin);
        return admin;
    }

    /** 不存在、密码错误、已停用都统一报"用户名或密码错误"。 */
    public AdminUser authenticate(String username, String rawPassword) {
        AdminUser admin = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, username));
        if (admin == null || !passwordEncoder.matches(rawPassword, admin.getPasswordHash())
                || admin.getStatus() == null || admin.getStatus() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return admin;
    }

    /** 管理员总数，启动时用来判断是否要初始化首个管理员。 */
    public long count() {
        return adminUserMapper.selectCount(null);
    }
}
