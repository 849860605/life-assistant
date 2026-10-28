package com.lifeassistant.core.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

/**
 * life-core 的基础 Bean：MyBatis-Plus 分页、密码编码器、外部 HTTP 客户端，并启用 LifeProperties。
 */
@Configuration
@EnableConfigurationProperties(LifeProperties.class)
public class CoreConfig {

    /** MyBatis-Plus 分页插件，按 PostgreSQL 方言生成分页 SQL。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /** 用户密码用 BCrypt 哈希。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 调用外部 HTTP 接口用的 RestClient。用 Apache HttpClient 是因为和风天气总是返回 gzip，
     * JDK 自带的客户端不会自动解压。
     */
    @Bean
    public RestClient.Builder externalRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(HttpClients.createSystem()));
    }
}
