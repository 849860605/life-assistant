package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Optional;

/** 普通用户的注册、登录校验和基本信息查询。 */
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
}
