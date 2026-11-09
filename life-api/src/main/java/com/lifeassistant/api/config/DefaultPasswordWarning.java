package com.lifeassistant.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * 启动时检查数据库密码是不是还是默认的 life：看起来是正式环境（profile 带 prod，或者数据库不在本机）时打一条警告。
 * 只提醒、不拦启动，免得改了配置之后服务起不来。
 */
@Slf4j
@Component
public class DefaultPasswordWarning implements ApplicationRunner {

    static final String DEFAULT_PASSWORD = "life";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final Environment env;
    private final String url;
    private final String password;

    public DefaultPasswordWarning(Environment env, @Value("${spring.datasource.url:}") String url,
                                  @Value("${spring.datasource.password:}") String password) {
        this.env = env;
        this.url = url;
        this.password = password;
    }

    /** 默认密码 + 像正式环境时打警告。 */
    @Override
    public void run(ApplicationArguments args) {
        if (shouldWarn(password, url, env.getActiveProfiles())) {
            log.warn("数据库密码还是默认的 \"life\"，正式环境请在 .env 里设置 DB_PASSWORD（数据库容器和应用要用同一个）");
        }
    }

    /** 密码是默认值，并且 profile 里有 prod、或者数据库地址不是本机时返回 true。 */
    static boolean shouldWarn(String password, String url, String[] profiles) {
        if (!DEFAULT_PASSWORD.equals(password)) {
            return false;
        }
        boolean prodProfile = Arrays.stream(profiles).anyMatch(p -> p.toLowerCase().contains("prod"));
        return prodProfile || !LOCAL_HOSTS.contains(host(url));
    }

    /** 从 jdbc:postgresql://主机:端口/库 里取主机名，取不到返回空串。 */
    static String host(String url) {
        if (url == null) {
            return "";
        }
        int start = url.indexOf("//");
        if (start < 0) {
            return "";
        }
        String rest = url.substring(start + 2);
        int end = rest.length();
        for (char c : new char[]{'/', '?'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String hostPort = rest.substring(0, end);
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            return close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        }
        int colon = hostPort.lastIndexOf(':');
        return (colon >= 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase();
    }
}
