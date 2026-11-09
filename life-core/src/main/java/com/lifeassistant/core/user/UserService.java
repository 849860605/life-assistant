package com.lifeassistant.core.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.auth.TicketVersions;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/** 普通用户的注册（后台建、邮箱注册）、登录校验、基本信息查询、绑定邮箱，以及后台用的新建、重置密码、启用停用。 */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 账号来源：自己用邮箱注册的 */
    public static final String SOURCE_EMAIL = "EMAIL";
    private static final String USERNAME_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TicketVersions ticketVersions;
    /** 账号不存在时也比对一次这个哈希，让"账号不存在"和"密码错"耗时一样，不能靠响应快慢猜账号 */
    private volatile String dummyHash;

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

    /** 校验账号密码（账号可以是用户名或绑定的邮箱），失败统一报"用户名或密码错误"，不区分是哪一项错；密码正确但账号已禁用时报 403。 */
    public User authenticate(String username, String rawPassword) {
        Optional<User> found = findByLogin(username);
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash());
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        User user = found.filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    /** 假哈希（第一次用到时生成），和真实密码哈希同样的强度。 */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("dummy-" + RANDOM.nextLong());
            dummyHash = hash;
        }
        return hash;
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

    /** 按登录名找：带 @ 的按邮箱找，否则按用户名找。 */
    public Optional<User> findByLogin(String login) {
        String text = login == null ? "" : login.trim();
        return text.contains("@") ? findByEmail(text) : findByUsername(text);
    }

    /** 按邮箱找（不区分大小写，库里存小写）；邮箱格式不对时返回空。 */
    public Optional<User> findByEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalized)));
    }

    /**
     * 这个邮箱（或者它的别名，比如 a+1@x.com、gmail 加点的写法）已经被别的账号用了时返回那个账号。
     * exceptUserId 是自己（绑定邮箱时排除自己），没有传 null。
     */
    public Optional<User> findEmailOwner(String email, Long exceptUserId) {
        Optional<User> exact = findByEmail(email).filter(u -> !u.getId().equals(exceptUserId));
        if (exact.isPresent()) {
            return exact;
        }
        String canonical = canonicalEmail(email);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getEmailCanonical, canonical).last("LIMIT 5"))
                .stream().filter(u -> !u.getId().equals(exceptUserId)).findFirst();
    }

    /**
     * 邮箱的归一写法：转小写，去掉 @ 前面 + 号及后面的部分（所有邮箱），gmail.com / googlemail.com 再去掉 @ 前面的点、
     * 域名统一成 gmail.com。和 V9_12_0 迁移里的 SQL 规则一致。
     */
    public static String canonicalEmail(String email) {
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int at = address.lastIndexOf('@');
        if (at <= 0) {
            return address;
        }
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }

    /**
     * 邮箱注册：邮箱已验证过，用户名自动生成（u 加 8 位随机字母数字），登录用邮箱；昵称不填用邮箱 @ 前面那段。
     * 邮箱或它的别名已被占用报 CONFLICT。
     */
    public User registerByEmail(String email, String rawPassword, String nickname) {
        String address = checkEmail(email);
        PasswordPolicy.check(rawPassword);
        if (findEmailOwner(address, null).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
        }
        String name = blankToNull(nickname);
        if (name == null) {
            name = address.substring(0, address.indexOf('@'));
        }
        User user = new User();
        user.setUsername(randomUsername());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(name.length() > 32 ? name.substring(0, 32) : name);
        user.setTenantId(0L);
        user.setTimezone("Asia/Shanghai");
        user.setStatus(1);
        user.setEmail(address);
        user.setEmailCanonical(canonicalEmail(address));
        user.setEmailVerifiedAt(OffsetDateTime.now());
        user.setSource(SOURCE_EMAIL);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 两个请求同时用同一个邮箱注册
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已注册，直接登录或找回密码");
        }
        return user;
    }

    /** 给已有用户绑定（或换）邮箱，邮箱已验证过；被别的账号占用时报 CONFLICT。 */
    public User bindEmail(long userId, String email) {
        String address = checkEmail(email);
        if (findEmailOwner(address, userId).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
        }
        try {
            userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                    .set(User::getEmail, address)
                    .set(User::getEmailCanonical, canonicalEmail(address))
                    .set(User::getEmailVerifiedAt, OffsetDateTime.now())
                    .set(User::getUpdatedAt, OffsetDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "这个邮箱已被其他账号绑定");
        }
        return get(userId);
    }

    /** 邮箱格式检查，返回去空格、转小写后的邮箱；不合规抛 BAD_REQUEST。 */
    public static String checkEmail(String email) {
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (address.length() > 254 || !address.matches("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱格式不对");
        }
        return address;
    }

    /** 生成一个没被占用的用户名：u 加 8 位小写字母数字。 */
    private String randomUsername() {
        for (int i = 0; i < 10; i++) {
            StringBuilder name = new StringBuilder("u");
            for (int j = 0; j < 8; j++) {
                name.append(USERNAME_CHARS.charAt(RANDOM.nextInt(USERNAME_CHARS.length())));
            }
            if (findByUsername(name.toString()).isEmpty()) {
                return name.toString();
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "生成用户名失败，请再试一次");
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

    /** 重置用户密码（后台重置、找回密码、自己改密码都走这里），按规则校验；之前签的下载票据一起作废。 */
    public void resetPassword(long userId, String rawPassword) {
        get(userId);
        PasswordPolicy.check(rawPassword);
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getPasswordHash, passwordEncoder.encode(rawPassword))
                .set(User::getUpdatedAt, OffsetDateTime.now()));
        ticketVersions.bump(userId);
    }

    /** 用户自己改密码：原密码不对报 401（由调用方计入错误次数），新密码按规则校验，不能和原密码一样。 */
    public void changeOwnPassword(long userId, String oldPassword, String newPassword) {
        User user = get(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "原密码不对");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能和原密码一样");
        }
        resetPassword(userId, newPassword);
    }

    /** 启用或停用用户；停用后登录会报"账号已被禁用"，下载票据作废，已有的登录态由调用方踢掉。 */
    public User setEnabled(long userId, boolean enabled) {
        User user = get(userId);
        user.setStatus(enabled ? 1 : 0);
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getStatus, user.getStatus())
                .set(User::getUpdatedAt, OffsetDateTime.now()));
        if (!enabled) {
            ticketVersions.bump(userId);
        }
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
