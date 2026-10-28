package com.lifeassistant.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：本地开发时允许前端开发服务器访问 /api/**。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 本地开发时前端（Vite）的地址，逗号分隔 */
    @Value("${life.web.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    /** 只放行配置的来源和 GET/POST/PUT/DELETE；不覆盖 /admin/api/**。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
