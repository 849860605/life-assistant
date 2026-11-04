package com.lifeassistant.api.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.lifeassistant.api.auth.StpAdminUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 登录拦截配置：用户接口和管理端接口分别用两套账号体系校验登录。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** /api/v1/** 校验用户登录，/admin/api/v1/** 校验管理员登录；登录接口和日历订阅放行。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            SaRouter.match("/api/v1/**")
                    // 登录、注册、要验证码、找回密码不需要登录（要验证码时绑定邮箱的用途在接口里自己查登录）；日历订阅用个人访问令牌，地图图片、高德 JS 地图转发和行程 PDF 用下载票据，都在接口里自己校验
                    .notMatch("/api/v1/auth/login", "/api/v1/auth/register-options", "/api/v1/auth/email-code",
                            "/api/v1/auth/register", "/api/v1/auth/password/reset", "/api/v1/calendar/feed/**", "/api/v1/maps/static",
                            "/api/v1/maps/amap/**",
                            "/api/v1/trips/*/pdf")
                    .check(r -> StpUtil.checkLogin());
            SaRouter.match("/admin/api/v1/**")
                    .notMatch("/admin/api/v1/auth/login")
                    .check(r -> StpAdminUtil.STP.checkLogin());
        })).addPathPatterns("/api/**", "/admin/api/**");
    }
}
