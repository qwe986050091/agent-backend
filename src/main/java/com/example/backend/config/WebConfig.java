package com.example.backend.config;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：CORS 跨域 + 登录拦截器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AdminInterceptor adminInterceptor,
                     RateLimitInterceptor rateLimitInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 允许本地开发地址与生产域名访问 /api
        // 注意：Spring 对不在白名单内的 Origin 直接返回 403，生产域名必须加进来
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://zlfshop.xyz",
                        "https://zlfshop.xyz")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 对话、会话管理、Skill 管理、知识库、反馈、管理后台接口必须登录；
        // /api/auth/** 与 /api/health 放行
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/chat/**", "/api/conversations/**", "/api/skills/**",
                        "/api/knowledge/**", "/api/usage/**", "/api/feedback/**", "/api/admin/**");
        // 对话入口用户级限流（频率/在途并发/日 token 预算）；仅占回合的两个发消息接口，
        // 不含 history 等只读接口。注册顺序在鉴权之后：preHandle 必能取到当前用户
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat", "/api/chat/stream");
        // 管理员白名单二次校验（注册顺序在后：先登录鉴权放入用户，再判管理员）
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}
