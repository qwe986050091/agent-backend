package com.example.backend.agent;

import com.example.backend.agent.trace.TraceData;
import com.example.backend.billing.BillingService;
import com.example.backend.observability.BackendMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * backend 侧 SSE 转发语义单测（P2-12）。
 *
 * {@link AgentClient#chatStream} 把 agent 的事件桥接给前端，其中 {@code dispatchEvent}
 * 是整个桥接的真实语义所在（ChatController 只是持有 emitter，转发逻辑全在这里）：
 *   1. 默认事件（token）转发并累积 fullAnswer
 *   2. memory 事件**不转发**，只提取摘要回传（backend 落库用）
 *   3. tool/rag/safety/usage 转发同名事件并累积进 trace（usage 额外做计费富化）
 *   4. [DONE] 静默；error 先转发再抛终态异常（连接仍正常收尾）
 *   5. 转发时前端已断开 → 抛 StreamClientClosedException（上层据此记 outcome=cancelled）
 *   6. trace-reset 清空累积并转发
 *
 * 纯单测：RestClient 不发起真实请求，emitter 用记录型假件。
 */
class AgentStreamForwardingTest {

    private AgentClient client;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        client = new AgentClient(
                "http://127.0.0.1:1",       // 本测试不发起真实请求
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                "test-token",
                CircuitBreaker.ofDefaults("test"),
                Retry.ofDefaults("test"),
                new ObjectMapper(),
                mock(BackendMetrics.class),
                billingService);
    }

    // ==================== 默认事件（token 片段） ====================

    @Test
    void defaultEventIsForwardedAndAccumulated() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        StringBuilder fullAnswer = new StringBuilder();
        String[] memorySummary = new String[]{null};
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();

        assertThatCode(() -> {
            client.dispatchEvent(null, "你好", fullAnswer, memorySummary, emitter, trace);
            client.dispatchEvent(null, "，世界", fullAnswer, memorySummary, emitter, trace);
        }).doesNotThrowAnyException();

        assertThat(fullAnswer.toString()).isEqualTo("你好，世界");
        assertThat(emitter.flat()).contains("你好").contains("，世界");
        // 默认事件不带事件名
        assertThat(emitter.flat()).doesNotContain("event:");
        assertThat(memorySummary[0]).isNull();
    }

    // ==================== memory：不转发，只提取摘要 ====================

    @Test
    void memoryEventIsNotForwardedButSummaryIsExtracted() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        String[] memorySummary = new String[]{null};

        assertThatCode(() -> client.dispatchEvent("memory", "{\"summary\":\"用户问了报销流程\"}",
                new StringBuilder(), memorySummary, emitter, new AgentClient.TraceAccumulator()))
                .doesNotThrowAnyException();

        assertThat(emitter.chunks).isEmpty();
        assertThat(memorySummary[0]).isEqualTo("用户问了报销流程");
    }

    @Test
    void memoryEventWithBlankOrBrokenPayloadIsIgnored() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        String[] memorySummary = new String[]{"旧摘要"};

        // 空白摘要不覆盖已有值
        assertThatCode(() -> client.dispatchEvent("memory", "{\"summary\":\"  \"}",
                new StringBuilder(), memorySummary, emitter, new AgentClient.TraceAccumulator()))
                .doesNotThrowAnyException();
        assertThat(memorySummary[0]).isEqualTo("旧摘要");

        // 畸形 JSON 只记 WARN，不抛
        assertThatCode(() -> client.dispatchEvent("memory", "{不是 JSON",
                new StringBuilder(), memorySummary, emitter, new AgentClient.TraceAccumulator()))
                .doesNotThrowAnyException();
        assertThat(memorySummary[0]).isEqualTo("旧摘要");
        assertThat(emitter.chunks).isEmpty();
    }

    // ==================== 命名事件：转发 + 累积 ====================

    @Test
    void toolRagSafetyEventsAreForwardedAndAccumulated() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();

        assertThatCode(() -> {
            client.dispatchEvent("tool", "{\"round\":1,\"name\":\"getCurrentTime\"}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
            client.dispatchEvent("rag", "{\"query\":\"报销流程\",\"segments\":[]}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
            client.dispatchEvent("safety", "{\"channel\":\"input\",\"score\":4,\"verdict\":\"none\"}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
        }).doesNotThrowAnyException();

        String flat = emitter.flat();
        assertThat(flat).contains("event:tool").contains("event:rag").contains("event:safety");

        TraceData snapshot = trace.snapshot();
        assertThat(snapshot.tools()).hasSize(1);
        assertThat(snapshot.recalls()).hasSize(1);
        assertThat(snapshot.safeties()).hasSize(1);
        assertThat(snapshot.tools().get(0).name()).isEqualTo("getCurrentTime");
    }

    @Test
    void delegationEventIsForwardedAndAccumulated() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();

        assertThatCode(() -> {
            client.dispatchEvent("delegation",
                    "{\"id\":\"p1\",\"agent\":\"planner\",\"success\":true,\"durationMs\":120,\"tokens\":300}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
            client.dispatchEvent("delegation",
                    "{\"id\":\"s1\",\"agent\":\"executor\",\"parentId\":\"p1\",\"success\":true,"
                            + "\"durationMs\":80,\"tokens\":150}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
        }).doesNotThrowAnyException();

        assertThat(emitter.flat()).contains("event:delegation");
        TraceData snapshot = trace.snapshot();
        assertThat(snapshot.delegations()).hasSize(2);
        assertThat(snapshot.delegations().get(1).agent()).isEqualTo("executor");
        assertThat(snapshot.delegations().get(1).parentId()).isEqualTo("p1");
    }

    @Test
    void usageEventIsForwardedWithCostEnrichment() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();
        when(billingService.cost(anyString(), any())).thenReturn(new BigDecimal("0.0123"));

        assertThatCode(() -> client.dispatchEvent("usage",
                "{\"model\":\"deepseek-v4-flash\",\"inputTokens\":10,\"outputTokens\":20,\"totalTokens\":30}",
                new StringBuilder(), new String[]{null}, emitter, trace))
                .doesNotThrowAnyException();

        String flat = emitter.flat();
        assertThat(flat).contains("event:usage").contains("costYuan").contains("0.0123");
        assertThat(trace.snapshot().usage().totalTokens()).isEqualTo(30);
    }

    @Test
    void usageEventFallsBackToRawForwardingWhenBillingFails() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(billingService.cost(anyString(), any())).thenThrow(new IllegalStateException("计费表损坏"));

        assertThatCode(() -> client.dispatchEvent("usage",
                "{\"model\":\"unknown\",\"totalTokens\":5}",
                new StringBuilder(), new String[]{null}, emitter, new AgentClient.TraceAccumulator()))
                .doesNotThrowAnyException();

        // 富化失败仍转发原始事件
        assertThat(emitter.flat()).contains("event:usage").contains("unknown");
    }

    @Test
    void doneMarkerIsSilentlyIgnored() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        StringBuilder fullAnswer = new StringBuilder();

        assertThatCode(() -> client.dispatchEvent(null, "[DONE]",
                fullAnswer, new String[]{null}, emitter, new AgentClient.TraceAccumulator()))
                .doesNotThrowAnyException();

        assertThat(emitter.chunks).isEmpty();
        assertThat(fullAnswer.toString()).isEmpty();
    }

    @Test
    void approvalRequestIsForwardedWithoutAccumulation() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();

        assertThatCode(() -> client.dispatchEvent("approval-request",
                "{\"toolName\":\"sendHttpRequest\"}",
                new StringBuilder(), new String[]{null}, emitter, trace))
                .doesNotThrowAnyException();

        assertThat(emitter.flat()).contains("event:approval-request").contains("sendHttpRequest");
        assertThat(trace.snapshot().isEmpty()).isTrue();
    }

    // ==================== error：先转发再抛终态异常 ====================

    @Test
    void errorEventIsForwardedThenThrowsTerminalException() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        assertThatThrownBy(() -> client.dispatchEvent("error",
                "{\"code\":\"CHAT_FAILED\",\"message\":\"模型超时\"}",
                new StringBuilder(), new String[]{null}, emitter, new AgentClient.TraceAccumulator()))
                .isInstanceOf(AgentClient.AgentStreamTerminalException.class);

        // 前端端口仍拿到可读原因（连接健康，不能截断成 network error）
        assertThat(emitter.flat()).contains("event:error").contains("模型超时");
        assertThat(emitter.completed).isFalse();
        assertThat(emitter.failed).isNull();
    }

    // ==================== trace-reset：清空累积 ====================

    @Test
    void traceResetClearsAccumulationAndIsForwarded() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        AgentClient.TraceAccumulator trace = new AgentClient.TraceAccumulator();

        assertThatCode(() -> {
            client.dispatchEvent("tool", "{\"round\":1,\"name\":\"t1\"}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
            client.dispatchEvent("usage", "{\"model\":\"m\",\"totalTokens\":9}",
                    new StringBuilder(), new String[]{null}, emitter, trace);
        }).doesNotThrowAnyException();
        assertThat(trace.snapshot().tools()).hasSize(1);
        assertThat(trace.snapshot().usage().totalTokens()).isEqualTo(9);

        assertThatCode(() -> client.dispatchEvent("trace-reset", "{}",
                new StringBuilder(), new String[]{null}, emitter, trace))
                .doesNotThrowAnyException();

        assertThat(emitter.flat()).contains("event:trace-reset");
        assertThat(trace.snapshot().tools()).isEmpty();
        assertThat(trace.snapshot().isEmpty()).isTrue();
    }

    // ==================== 前端断开 ====================

    @Test
    void sendFailureOnDefaultEventThrowsStreamClientClosed() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        emitter.failSendWith = new IOException("broken pipe");

        assertThatThrownBy(() -> client.dispatchEvent(null, "一些 token",
                new StringBuilder(), new String[]{null}, emitter, new AgentClient.TraceAccumulator()))
                .isInstanceOf(AgentClient.StreamClientClosedException.class);
    }

    @Test
    void sendFailureOnNamedEventThrowsStreamClientClosed() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        emitter.failSendWith = new IOException("broken pipe");

        assertThatThrownBy(() -> client.dispatchEvent("tool", "{\"round\":1,\"name\":\"t1\"}",
                new StringBuilder(), new String[]{null}, emitter, new AgentClient.TraceAccumulator()))
                .isInstanceOf(AgentClient.StreamClientClosedException.class);
    }

    // ==================== 测试夹具 ====================

    /** 记录型 SseEmitter：覆写 send 系列方法收集片段（send(SseEventBuilder) 内部走 super.send，必须单独覆写） */
    static final class RecordingSseEmitter extends SseEmitter {
        final List<Object> chunks = new ArrayList<>();
        boolean completed;
        Throwable failed;
        IOException failSendWith;

        @Override
        public void send(Object object) throws IOException {
            record(object);
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            record(object);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (DataWithMediaType entry : builder.build()) {
                record(entry.getData());
            }
        }

        private void record(Object data) throws IOException {
            if (failSendWith != null) {
                throw failSendWith;
            }
            chunks.add(data);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            failed = ex;
        }

        String flat() {
            StringBuilder sb = new StringBuilder();
            for (Object c : chunks) {
                sb.append(c);
            }
            return sb.toString();
        }
    }
}