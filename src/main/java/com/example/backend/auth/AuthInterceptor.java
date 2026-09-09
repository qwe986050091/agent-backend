package com.example.backend.auth;

import com.example.backend.user.UserEntity;
import com.example.backend.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录鉴权拦截器
 *
 * 从 Authorization: Bearer <token>（或 X-Auth-Token 头）取令牌，
 * 经 Redis 会话校验（自动滑动续期）得到 userId 后查库加载用户，放入 request 属性。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** request 属性名：当前登录用户 */
    public static final String CURRENT_USER_ATTR = "currentUser";

    private final SessionStore sessionStore;
    private final UserRepository userRepository;

    public AuthInterceptor(SessionStore sessionStore, UserRepository userRepository) {
        this.sessionStore = sessionStore;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 放行 CORS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractToken(request);
        Long userId = sessionStore.validate(token);
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);

        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已过期，请重新登录\"}");
            return false;
        }

        request.setAttribute(CURRENT_USER_ATTR, user);
        return true;
    }

    /** 从请求头提取 token */
    public static String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String token = request.getHeader("X-Auth-Token");
        return token == null || token.isBlank() ? null : token.trim();
    }
}
