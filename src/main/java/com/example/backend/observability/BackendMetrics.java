package com.example.backend.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * backend 侧 Micrometer 指标（P2-10，/actuator/prometheus 暴露）。
 *
 *  - backend.chat.duration{mode,outcome}：对话回合（count=QPS，outcome=success/unavailable/error/rejected/cancelled）
 *  - backend.agent.http.duration{api,outcome}：调 agent HTTP 耗时（count=调用量/错误率）
 *  - backend.sse.active：在途 SSE 连接数
 *  - backend.agent.cb.state{state}：熔断器状态（当前状态=1，其余=0）
 *  - backend.agent.cb.failure_rate：当前失败率（0~1）
 *
 * 指标异常不得影响业务。
 */
@Component
public class BackendMetrics {

    private static final Logger log = LoggerFactory.getLogger(BackendMetrics.class);

    private final MeterRegistry registry;
    private final AtomicInteger sseActive = new AtomicInteger(0);

    public BackendMetrics(MeterRegistry registry, CircuitBreaker agentCircuitBreaker) {
        this.registry = registry;
        registry.gauge("backend.sse.active", sseActive);
        registerCircuitBreakerGauges(agentCircuitBreaker);
    }

    /** 记录一次对话回合耗时 */
    public void recordChat(String mode, String outcome, long durationMs) {
        safe(() -> {
            if (durationMs < 0) {
                return;
            }
            Timer.builder("backend.chat.duration")
                    .tags("mode", tag(mode), "outcome", tag(outcome))
                    .publishPercentileHistogram(true)
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    /** 包装一次 agent HTTP 调用并计时（Supplier 路径） */
    public <T> T timeAgentCall(String api, Supplier<T> action) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            recordAgentHttp(api, outcome, (System.nanoTime() - start) / 1_000_000L);
        }
    }

    /** Runnable 路径（无返回值的 RAG 调用） */
    public void runAgentCall(String api, Runnable action) {
        timeAgentCall(api, () -> {
            action.run();
            return null;
        });
    }

    public void recordAgentHttp(String api, String outcome, long durationMs) {
        safe(() -> {
            if (durationMs < 0) {
                return;
            }
            Timer.builder("backend.agent.http.duration")
                    .tags("api", tag(api), "outcome", tag(outcome))
                    .publishPercentileHistogram(true)
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    public void changeSseActive(int delta) {
        safe(() -> sseActive.addAndGet(delta));
    }

    /**
     * 记录一次回答反馈提交（P2-11.1）。
     *
     * @param outcome up / down / cancel
     * @param reason  点踩原因码（赞/取消为 null，标签落 none）
     */
    public void recordFeedback(String outcome, String reason) {
        safe(() -> registry.counter("backend.feedback.total",
                "outcome", tag(outcome),
                "reason", reason == null || reason.isBlank() ? "none" : tag(reason))
                .increment());
    }

    /** 熔断状态 Gauge：抓取时实时计算，当前状态输出 1 其余输出 0；附失败率 */
    private void registerCircuitBreakerGauges(CircuitBreaker cb) {
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            registry.gauge("backend.agent.cb.state",
                    io.micrometer.core.instrument.Tags.of("state", state.name()),
                    cb,
                    breaker -> breaker.getState() == state ? 1d : 0d);
        }
        registry.gauge("backend.agent.cb.failure.rate", cb, breaker -> {
            float rate = breaker.getMetrics().getFailureRate();
            // 样本不足时 Resilience4j 返回 -1，指标按 0 输出避免负值
            return rate < 0 ? 0d : rate / 100d;
        });
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("[Metrics] 指标记录异常（不影响业务）: {}", e.getMessage());
        }
    }

    private static String tag(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[\\r\\n]", "_");
    }
}
