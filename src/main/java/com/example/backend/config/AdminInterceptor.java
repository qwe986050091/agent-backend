package com.example.backend.config;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员拦截器（P2-11.1）：保护 /api/admin/**。
 * 必须注册在 {@link AuthInterceptor} 之后——依赖其放入 request 的当前登录用户。
 * 非白名单用户返回 403 JSON；白名单为空时无人可访问（fail-closed）。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final AdminProperties adminProperties;

    public AdminInterceptor(AdminProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (user == null || !adminProperties.isAdmin(user.getUsername())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"需要管理员权限\"}");
            return false;
        }
        return true;
    }
}
