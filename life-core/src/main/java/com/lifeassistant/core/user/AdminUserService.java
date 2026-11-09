package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** 管理员账号：创建、登录校验、列表、改自己密码、重置别人密码、启用停用。 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    public static final String SUPER = "SUPER";
    public static final String ADMIN = "ADMIN";
    private static final Set<String> ROLES = Set.of(SUPER, ADMIN);

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    /** 账号不存在时也比对一次这个哈希，让耗时和密码错时一样 */
    private volatile String dummyHash;

    /** 启动时初始化首个管理员用：超级管理员，密码沿用环境变量里配的，不按规则校验。 */
    public AdminUser create(String username, String rawPassword) {
        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setStatus(1);
        admin.setRole(SUPER);
        adminUserMapper.insert(admin);
        return admin;
    }

    /** 后台新建管理员：用户名不能重复，密码按规则校验，role 只能是 SUPER 或 ADMIN。 */
    public AdminUser create(String username, String rawPassword, String role, String nickname) {
        String name = UserService.checkUsername(username);
        if (!ROLES.contains(role)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色只能是 SUPER 或 ADMIN");
        }
        PasswordPolicy.check(rawPassword);
        if (adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, name)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "管理员用户名已存在");
        }
        AdminUser admin = new AdminUser();
        admin.setUsername(name);
        admin.setNickname(UserService.blankToNull(nickname));
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setStatus(1);
        admin.setRole(role);
        adminUserMapper.insert(admin);
        return admin;
    }

    /** 不存在、密码错误、已停用都统一报"用户名或密码错误"；成功时记下登录时间。 */
    public AdminUser authenticate(String username, String rawPassword) {
        AdminUser admin = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, username));
        if (admin == null) {
            passwordEncoder.matches(rawPassword, dummyHash());
        }
        if (admin == null || !passwordEncoder.matches(rawPassword, admin.getPasswordHash())
                || admin.getStatus() == null || admin.getStatus() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        adminUserMapper.update(null, new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, admin.getId())
                .set(AdminUser::getLastLoginAt, OffsetDateTime.now()));
        return admin;
    }

    /** 假哈希（第一次用到时生成），和真实密码哈希同样的强度。 */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("dummy-" + System.nanoTime());
            dummyHash = hash;
        }
        return hash;
    }

    /** 按 id 取管理员，不存在报 404。 */
    public AdminUser get(long id) {
        AdminUser admin = adminUserMapper.selectById(id);
        if (admin == null) {
            throw BizException.notFound("管理员");
        }
        return admin;
    }

    /** 全部管理员，按创建先后排。 */
    public List<AdminUser> list() {
        return adminUserMapper.selectList(new LambdaQueryWrapper<AdminUser>().orderByAsc(AdminUser::getId));
    }

    /** 改自己的密码：原密码要对，新密码按规则校验且不能和原密码一样。 */
    public void changeOwnPassword(long id, String oldPassword, String newPassword) {
        AdminUser admin = get(id);
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不对");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能和原密码一样");
        }
        setPassword(id, newPassword);
    }

    /** 重置别的管理员的密码（超级管理员操作），按规则校验。 */
    public void resetPassword(long id, String newPassword) {
        get(id);
        setPassword(id, newPassword);
    }

    /** 启用或停用；不能停用自己，也不能停用最后一个启用的超级管理员（免得没人能管理管理员）。 */
    public AdminUser setEnabled(long operatorId, long id, boolean enabled) {
        AdminUser admin = get(id);
        if (!enabled && operatorId == id) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用自己");
        }
        if (!enabled && SUPER.equals(admin.getRole()) && admin.getStatus() != null && admin.getStatus() == 1
                && activeSupers() <= 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这是最后一个启用的超级管理员，不能停用");
        }
        admin.setStatus(enabled ? 1 : 0);
        adminUserMapper.update(null, new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id)
                .set(AdminUser::getStatus, admin.getStatus()));
        return admin;
    }

    /** 管理员总数，启动时用来判断是否要初始化首个管理员。 */
    public long count() {
        return adminUserMapper.selectCount(null);
    }

    private long activeSupers() {
        return adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getRole, SUPER).eq(AdminUser::getStatus, 1));
    }

    private void setPassword(long id, String rawPassword) {
        PasswordPolicy.check(rawPassword);
        adminUserMapper.update(null, new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id)
                .set(AdminUser::getPasswordHash, passwordEncoder.encode(rawPassword)));
    }
}
