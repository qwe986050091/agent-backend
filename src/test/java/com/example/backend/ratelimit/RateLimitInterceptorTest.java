package com.example.backend.ratelimit;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.observability.BackendMetrics;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 单元测试：放行/拒绝的响应行为、名额释放时机、OPTIONS/无用户兜底。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RateLimitService service;
    @Mock
    private BackendMetrics metrics;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private RateLimitInterceptor interceptor;

    private UserEntity user(String username) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        return u;
    }

    @Test
    void allowed_passesAndReleasesOnAfterCompletion() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).thenReturn(user("alice"));
        when(service.tryAcquire(org.mockito.ArgumentMatchers.any())).thenReturn(RateLimitService.Decision.allow());

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        verify(request).setAttribute("rateLimitAcquiredUser", "alice");

        // 回合结束（SSE 为异步分发完成后）：据已记录属性释放
        when(request.getAttribute("rateLimitAcquiredUser")).thenReturn("alice");
        interceptor.afterCompletion(request, response, null, null);
        verify(service).release("alice");
    }

    @Test
    void rejected_returns429JsonAndDoesNotRelease() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).thenReturn(user("alice"));
        when(service.tryAcquire(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RateLimitService.Decision.reject("RATE_LIMITED", "请稍后再试"));
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);
        when(response.getWriter()).thenReturn(writer);

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        writer.flush();

        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        verify(metrics).recordRateLimitRejected("RATE_LIMITED");
        assertThat(body.toString()).contains("RATE_LIMITED").contains("请稍后再试");
        // 被拒未占名额：不写占位属性；afterCompletion 即使回调也不触发 release
        verify(request, never()).setAttribute(org.mockito.ArgumentMatchers.eq("rateLimitAcquiredUser"),
                org.mockito.ArgumentMatchers.any());
        // 未设占位属性（mock 默认返回 null）：afterCompletion 不应触发 release
        interceptor.afterCompletion(request, response, null, null);
        verify(service, never()).release(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void optionsRequest_skipsRateLimit() throws Exception {
        when(request.getMethod()).thenReturn("OPTIONS");

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        verifyNoInteractions(service);
    }

    @Test
    void missingUserAttribute_passesThroughToAuthSafetyNet() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).thenReturn(null);

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        verifyNoInteractions(service);
    }
}
