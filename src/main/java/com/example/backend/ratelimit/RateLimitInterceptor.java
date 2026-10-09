package com.example.backend.ratelimit;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.observability.BackendMetrics;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对话入口限流拦截器（P0-4），仅挂在 POST /api/chat 与 /api/chat/stream。
 *
 * 注册顺序在 {@link AuthInterceptor} 之后：此处一定能取到 CURRENT_USER_ATTR。
 * 在途名额在 afterCompletion 释放——SSE 请求在 emitter 结束触发异步分发回容器时
 * 才回调 afterCompletion，因此同步/流式两条路径都能被正确覆盖；
 * preHandle 返回 false（被拒）时不会触发 afterCompletion，无需释放。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** request 属性名：已占名额的用户名（afterCompletion 据此释放） */
    private static final String ACQUIRED_USER_ATTR = "rateLimitAcquiredUser";

    private final RateLimitService rateLimitService;
    private final BackendMetrics metrics;

    public RateLimitInterceptor(RateLimitService rateLimitService, BackendMetrics metrics) {
        this.rateLimitService = rateLimitService;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (user == null) {
            // 理论不可达（鉴权拦截器在前），放行交由鉴权兜底
            return true;
        }

        RateLimitService.Decision decision = rateLimitService.tryAcquire(user);
        if (decision.allowed()) {
            request.setAttribute(ACQUIRED_USER_ATTR, user.getUsername());
            return true;
        }

        metrics.recordRateLimitRejected(decision.code());
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        // 频率窗口固定 60s；预算/并发拒绝也给保守值，客户端可据此退避
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"code\":\"" + decision.code()
                + "\",\"message\":\"" + decision.message() + "\"}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object acquiredUser = request.getAttribute(ACQUIRED_USER_ATTR);
        if (acquiredUser != null) {
            rateLimitService.release(acquiredUser.toString());
        }
    }
}
