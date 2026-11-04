package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/** 普通用户的注册、登录校验、基本信息查询，以及后台用的新建、重置密码、启用停用。 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 注册新用户：用户名不能重复，昵称默认同用户名，时区默认 Asia/Shanghai。 */
    public User create(String username, String rawPassword, String nickname) {
        if (findByUsername(username).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname == null ? username : nickname);
        user.setTenantId(0L);
        user.setTimezone("Asia/Shanghai");
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    /** 校验账号密码，失败统一报"用户名或密码错误"，不区分是哪一项错；密码正确但账号已禁用时报 403。 */
    public User authenticate(String username, String rawPassword) {
        User user = findByUsername(username)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    /** 按 id 取用户，不存在报 404。 */
    public User get(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户");
        }
        return user;
    }

    /** 用户所在时区，工具解析和展示时间都以它为准。 */
    public ZoneId zoneOf(long userId) {
        return ZoneId.of(get(userId).getTimezone());
    }

    /** 按用户名精确查找。 */
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)));
    }

    /** 用户总数，启动时用来判断是否要初始化首个用户。 */
    public long count() {
        return userMapper.selectCount(null);
    }

    /** 后台新建用户：用户名 3~32 位字母数字下划线，密码按规则校验，昵称默认同用户名。 */
    public User createByAdmin(String username, String rawPassword, String nickname) {
        String name = checkUsername(username);
        PasswordPolicy.check(rawPassword);
        return create(name, rawPassword, blankToNull(nickname));
    }

    /** 后台重置用户密码，按规则校验。 */
    public void resetPassword(long userId, String rawPassword) {
        get(userId);
        PasswordPolicy.check(rawPassword);
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getPasswordHash, passwordEncoder.encode(rawPassword))
                .set(User::getUpdatedAt, OffsetDateTime.now()));
    }

    /** 启用或停用用户；停用后登录会报"账号已被禁用"，已有的登录态由调用方踢掉。 */
    public User setEnabled(long userId, boolean enabled) {
        User user = get(userId);
        user.setStatus(enabled ? 1 : 0);
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getStatus, user.getStatus())
                .set(User::getUpdatedAt, OffsetDateTime.now()));
        return user;
    }

    /** 记下最近一次登录时间，后台列表里看谁还在用。 */
    public void markLogin(long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getLastLoginAt, OffsetDateTime.now()));
    }

    /** 用户名规则（用户和管理员共用）：3~32 位字母、数字、下划线、点、减号；不合规抛 BAD_REQUEST。 */
    public static String checkUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9_.-]{3,32}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名要 3 到 32 位，只能用字母、数字、下划线、点和减号");
        }
        return name;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
