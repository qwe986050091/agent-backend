package com.example.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 入站 traceId 过滤器（backend 侧，全链路可观测 P2-10）。
 *
 * - 采用前端/调用方上送的 {@code X-Trace-Id}（合法 {@code [A-Za-z0-9-]{8,64}}），
 *   缺失/非法时兜底生成 UUID；
 * - 写 MDC 供日志 pattern 输出，并作为请求属性供 Controller 交接给 SSE 工作线程；
 * - 响应头回显，前端可用同一 id 与服务端日志对账；请求结束清理 MDC。
 */
@Component
@Order(1)
public class TraceMdcFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_REQUEST_ATTR = "traceId";
    public static final String MDC_TRACE_ID = "traceId";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = normalize(request.getHeader(TRACE_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_TRACE_ID, traceId);
        request.setAttribute(TRACE_REQUEST_ATTR, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** 合法（非空、字符集与长度合规）返回原值，否则 null */
    public static String normalize(String traceId) {
        if (traceId == null) {
            return null;
        }
        String trimmed = traceId.trim();
        return TRACE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * 把任务包装为「携带当前线程 traceId 执行，结束后清理」的任务，
     * 用于提交到线程池（SSE 转发、RAG 异步索引），避免工作线程复用串 traceId。
     */
    public static Runnable wrapWithMdc(Runnable task) {
        String traceId = MDC.get(MDC_TRACE_ID);
        return () -> {
            if (traceId != null) {
                MDC.put(MDC_TRACE_ID, traceId);
            }
            try {
                task.run();
            } finally {
                MDC.remove(MDC_TRACE_ID);
            }
        };
    }
}
