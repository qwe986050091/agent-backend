package com.example.backend.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * backend → agent 调用的韧性配置（熔断器 + 重试）。
 *
 * 熔断策略：
 *  - 滑动窗口（计数型）统计 agent 调用失败率，超过阈值进入 OPEN（快速失败，不再打已挂的 agent）
 *  - 等待 wait-in-open 后进入 HALF_OPEN，放行少量探测调用，成功则关闭、失败则重新 OPEN
 *  - <b>不把慢调用计入熔断</b>（slowCallRateThreshold=100）：SSE 长流、长回复是正常形态，
 *    只有真正的连接失败/5xx 才算故障
 *  - 4xx 参数错误（IllegalArgumentException 等）不计入失败率，避免错误请求把熔断顶开
 *
 * 重试策略（仅用于短调用，SSE 流式不重试）：
 *  - 仅对连接失败/读超时（ResourceAccessException）、agent 5xx（HttpServerErrorException）重试
 *  - 4xx、业务异常不重试
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreaker agentCircuitBreaker(
            @Value("${agent.resilience.cb.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${agent.resilience.cb.sliding-window-size:20}") int slidingWindowSize,
            @Value("${agent.resilience.cb.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${agent.resilience.cb.wait-in-open:30s}") Duration waitInOpen,
            @Value("${agent.resilience.cb.permitted-calls-in-half-open:3}") int permittedInHalfOpen) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitInOpen)
                .permittedNumberOfCallsInHalfOpenState(permittedInHalfOpen)
                // SSE 长流/长回复属正常，慢调用一律不计入熔断
                .slowCallRateThreshold(100f)
                // 4xx（参数错误/内部 token 不匹配等客户端/配置问题）不算 agent 服务故障，不顶开熔断
                .ignoreExceptions(IllegalArgumentException.class, HttpClientErrorException.class)
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("agent", config);
        breaker.getEventPublisher()
                .onStateTransition(e -> log.warn("[Resilience] agent 熔断器状态变更: {} -> {}（当前失败率 {}%）",
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState(),
                        breaker.getMetrics().getFailureRate()));
        log.info("[Resilience] agent 熔断器初始化: 失败率阈值{}%, 窗口{}/最少{}次, OPEN等待{}s",
                (int) failureRateThreshold, slidingWindowSize, minimumNumberOfCalls, waitInOpen.getSeconds());
        return breaker;
    }

    @Bean
    public Retry agentRetry(
            @Value("${agent.resilience.retry.max-attempts:2}") int maxAttempts,
            @Value("${agent.resilience.retry.wait-duration:1s}") Duration waitDuration) {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(waitDuration)
                .retryOnException(ResilienceConfig::isRetriable)
                .failAfterMaxAttempts(true)
                .build();

        Retry retry = Retry.of("agent", config);
        retry.getEventPublisher()
                .onRetry(e -> log.warn("[Resilience] agent 调用失败，准备第 {} 次重试: {}",
                        e.getNumberOfRetryAttempts() + 1,
                        e.getLastThrowable() == null ? "未知异常" : e.getLastThrowable().toString()));
        log.info("[Resilience] agent 重试初始化: 最多{}次, 间隔{}s（仅连接失败/读超时/5xx 重试）",
                maxAttempts, waitDuration.getSeconds());
        return retry;
    }

    /** 可重试判定：连接层故障（连接拒绝/读超时）与 agent 5xx；4xx/业务异常不重试 */
    private static boolean isRetriable(Throwable e) {
        if (e instanceof ResourceAccessException || e instanceof IOException) {
            return true;
        }
        if (e instanceof HttpServerErrorException) {
            return true;
        }
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException || cause instanceof IOException) {
            return true;
        }
        return false;
    }
}
