package com.example.backend.config;

import com.example.backend.auth.AuthInterceptor;
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

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
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
        // 对话、会话管理、Skill 管理、知识库接口必须登录；/api/auth/** 与 /api/health 放行
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/chat/**", "/api/conversations/**", "/api/skills/**",
                        "/api/knowledge/**", "/api/usage/**");
    }
}
