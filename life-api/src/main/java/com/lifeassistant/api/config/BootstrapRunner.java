package com.lifeassistant.api.config;

import com.lifeassistant.core.user.AdminUserService;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 首次启动时创建账号：S0 没有注册功能，用户和管理员都由环境变量初始化。
 * 已经有账号时什么都不做。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

    private final UserService userService;
    private final AdminUserService adminUserService;

    @Value("${life.bootstrap.username:}")
    private String username;
    @Value("${life.bootstrap.password:}")
    private String password;
    @Value("${life.bootstrap.admin-username:}")
    private String adminUsername;
    @Value("${life.bootstrap.admin-password:}")
    private String adminPassword;

    /** 没有用户时用 LIFE_USERNAME/LIFE_PASSWORD 建号（未配置则打警告）；没有管理员且配置齐全时建管理员。 */
    @Override
    public void run(ApplicationArguments args) {
        if (userService.count() == 0) {
            if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
                userService.create(username, password, username);
                log.info("已创建初始用户 {}", username);
            } else {
                log.warn("还没有任何用户。请在 .env 里设置 LIFE_USERNAME 和 LIFE_PASSWORD 后重启");
            }
        }
        if (adminUserService.count() == 0 && StringUtils.hasText(adminUsername) && StringUtils.hasText(adminPassword)) {
            adminUserService.create(adminUsername, adminPassword);
            log.info("已创建初始管理员 {}", adminUsername);
        }
    }
}
