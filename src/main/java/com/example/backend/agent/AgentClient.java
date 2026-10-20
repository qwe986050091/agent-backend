package com.example.backend.agent;

import com.example.backend.agent.trace.DelegationData;
import com.example.backend.agent.trace.RagData;
import com.example.backend.agent.trace.SafetyData;
import com.example.backend.agent.trace.ToolData;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.agent.trace.UsageData;
import com.example.backend.billing.BillingService;
import com.example.backend.common.TraceMdcFilter;
import com.example.backend.observability.BackendMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent 服务 HTTP 客户端
 *
 * 封装对 agent 工程（默认 http://localhost:8080）的调用。
 *
 * <b>无状态化协议：</b>agent 不保存任何跨请求状态，对话请求由本客户端把
 * 持久化的摘要（agent_memory 表）、近期历史（chat_message 表）、已挂载技能定义
 * （skill / user_skill 表）随 payload 一次性下发；agent 在响应中回传最新摘要
 * （同步 JSON 的 summary 字段 / SSE 的 event: memory），由 backend 落库。
 *
 * <b>韧性（Resilience4j）：</b>
 *  - 同步短调用（chat）：重试（仅连接失败/读超时/5xx）+ 熔断器
 *  - SSE 流式（chatStream）：只过熔断器（流式不可重试；慢调用不计熔断）
 *  - RAG 索引/删除：只过熔断器（索引非幂等，不重试；调用方均有 best-effort 容错）
 *
 * <b>连接池：</b>使用 JDK 内置 HttpClient（HTTP/2，自带连接复用池），
 * 替代无连接复用的 SimpleClientHttpRequestFactory。
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final BackendMetrics metrics;
    private final BillingService billingService;

    public AgentClient(@Value("${agent.service.base-url}") String baseUrl,
                       @Value("${agent.service.connect-timeout:5s}") Duration connectTimeout,
                       @Value("${agent.service.read-timeout:120s}") Duration readTimeout,
                       @Value("${agent.service.http-pool-threads:8}") int poolThreads,
                       @Value("${agent.service.internal-token:dev-internal-token}") String internalToken,
                       CircuitBreaker agentCircuitBreaker,
                       Retry agentRetry,
                       ObjectMapper objectMapper,
                       BackendMetrics metrics,
                       BillingService billingService) {
        this.circuitBreaker = agentCircuitBreaker;
        this.retry = agentRetry;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.billingService = billingService;

        // JDK HttpClient：内置连接池（HTTP/2 多路复用），独立有界线程池处理响应
        AtomicInteger seq = new AtomicInteger();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(Executors.newFixedThreadPool(poolThreads, r -> {
                    Thread t = new Thread(r, "agent-http-client-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // 服务间鉴权 + 全链路 traceId：所有请求（含 SSE exchange/RAG）统一携带两个头
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("X-Agent-Token", internalToken);
                    String traceId = MDC.get(TraceMdcFilter.MDC_TRACE_ID);
                    if (traceId == null || traceId.isBlank()) {
                        // 脱离入站上下文的调用（如异步任务漏接）兜底生成，保证 agent 侧也能串日志
                        traceId = UUID.randomUUID().toString();
                    }
                    request.getHeaders().set(TraceMdcFilter.TRACE_HEADER, traceId);
                    return execution.execute(request, body);
                })
                .build();
        log.info("[AgentClient] Agent 服务地址: {}（无状态协议 + 连接池 + 熔断/重试 + 内部鉴权）", baseUrl);
    }

    // ==================== 对话协议 DTO ====================

    /**
     * 历史/当前消息图片（与 agent 端 ChatImage 对应；仅 user 消息携带）。
     * 注意：agent 端 DTO 的 JSON 字段是 {@code data}（不是 base64Data），
     * 若漏配 @JsonProperty，agent 反序列化得到 data=null → INVALID_IMAGE，
     * 且其 stream 端 produces=text/event-stream 下 JSON 错误体无法写出，最终表现为 500。
     */
    public record AgentChatImage(String mimeType,
                                 @com.fasterxml.jackson.annotation.JsonProperty("data") String base64Data) {}

    /** 历史消息（与 agent 端 HistoryMessage 对应；P1-8 起 user 消息可带图片） */
    public record AgentChatMessage(String role, String content, List<AgentChatImage> images) {
        /** 纯文本历史消息便捷构造 */
        public AgentChatMessage(String role, String content) {
            this(role, content, List.of());
        }
    }

    /** 对话请求 payload：userId + 会话 + 消息 + 摘要 + 近期历史 + 已挂载技能定义 + 会话级免确认工具
     *  + 当前消息图片（P1-8 多模态） */
    public record AgentChatPayload(String userId,
                                   Long conversationId,
                                   String message,
                                   String summary,
                                   List<AgentChatMessage> history,
                                   List<Map<String, Object>> skills,
                                   List<String> allowedTools,
                                   List<AgentChatImage> images) {
        /** 兼容无图片构造 */
        public AgentChatPayload(String userId, Long conversationId, String message, String summary,
                                List<AgentChatMessage> history, List<Map<String, Object>> skills,
                                List<String> allowedTools) {
            this(userId, conversationId, message, summary, history, skills, allowedTools, List.of());
        }
    }

    /** 同步对话结果：回复文本 + 回合后最新摘要 + traceId/trace 数据（用量/工具/召回） */
    public record AgentChatResult(String answer, String summary, String traceId, TraceData trace) {}

    /** SSE 正常结束回调：完整回复 + 最新摘要 + trace 数据 */
    @FunctionalInterface
    public interface StreamCompleteListener {
        void onComplete(String fullAnswer, String summary, TraceData traceData);
    }

    /** SSE 终态错误回调：异常 + 出错时已累积的 trace（取消时按已收 usage 记账） */
    @FunctionalInterface
    public interface StreamTerminalErrorListener {
        void onTerminalError(Throwable error, TraceData accumulatedTrace);
    }

    /** 前端断开导致流终止的标记异常（区别于 agent 故障/熔断，用于 outcome=cancelled 判定） */
    public static class StreamClientClosedException extends RuntimeException {
        public StreamClientClosedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * agent 在 SSE 响应已提交后通过 {@code event: error} 报告的终态业务错误
     * （如上游模型拒绝图片）。连接本身健康，错误事件已透传前端，
     * 外层必须正常 complete 前端 emitter，不能 completeWithError 把流截断成 network error。
     */
    public static class AgentStreamTerminalException extends RuntimeException {
        public AgentStreamTerminalException(String rawEventData) {
            super(rawEventData);
        }
    }

    /**
     * 流开始前被 agent 内容安全闸门拒绝（400 SAFETY_BLOCKED，P4-17）。
     * backend 据此补录 prompt_safety_record 审计行，并以 400 干净错误返回前端。
     */
    public static class AgentSafetyBlockedException extends RuntimeException {
        public AgentSafetyBlockedException(String detail) {
            super(detail);
        }
    }

    /** 是否为 agent 安全闸门拒绝（供 ChatController/GlobalExceptionHandler 识别） */
    public static boolean isSafetyBlocked(Throwable e) {
        return e instanceof AgentSafetyBlockedException;
    }

    /** 从异常文案提取对用户可读的安全拒绝提示（无则返回 null，由调用方用兜底文案） */
    public static String safetyBlockedUserMessage(Throwable e) {
        if (!(e instanceof AgentSafetyBlockedException) || e.getMessage() == null) {
            return null;
        }
        int idx = e.getMessage().indexOf("SAFETY_BLOCKED]");
        return idx < 0 ? null : e.getMessage().substring(idx + "SAFETY_BLOCKED]".length()).strip();
    }

    /** 同步对话，返回 Agent 回复文本与最新摘要（重试在内、熔断在外；熔断打开时快速失败） */
    public AgentChatResult chat(AgentChatPayload payload) {
        // 装饰顺序：CB(retry(metrics(doChat))) —— 重试在一次熔断器统计内进行，指标只记整体耗时
        Supplier<AgentChatResult> supplier = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry,
                        () -> metrics.timeAgentCall("chat", () -> doChat(payload))));
        try {
            return supplier.get();
        } catch (CallNotPermittedException e) {
            throw new IllegalStateException("AI 服务暂时不可用（熔断中），请稍后再试");
        }
    }

    private AgentChatResult doChat(AgentChatPayload payload) {
        Map resp = restClient.post()
                .uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);
        String answer = resp == null || resp.get("answer") == null ? "" : resp.get("answer").toString();
        Object summary = resp == null ? null : resp.get("summary");
        String traceId = resp != null && resp.get("traceId") != null ? resp.get("traceId").toString() : null;
        TraceData traceData = resp == null ? TraceData.empty() : parseTraceData(resp, objectMapper);
        return new AgentChatResult(answer, summary == null ? null : summary.toString(), traceId, traceData);
    }

    /** 从 agent 同步响应 Map 解析 trace 数据（任何字段缺失/异常都退化为空，不影响对话） */
    @SuppressWarnings("unchecked")
    static TraceData parseTraceData(Map<String, Object> resp, ObjectMapper mapper) {
        try {
            UsageData usage = resp.get("usage") == null
                    ? UsageData.empty()
                    : mapper.convertValue(resp.get("usage"), UsageData.class);
            List<ToolData> tools = resp.get("tools") == null
                    ? List.of()
                    : mapper.convertValue(resp.get("tools"),
                            mapper.getTypeFactory().constructCollectionType(List.class, ToolData.class));
            List<RagData> recalls = resp.get("retrievals") == null
                    ? List.of()
                    : mapper.convertValue(resp.get("retrievals"),
                            mapper.getTypeFactory().constructCollectionType(List.class, RagData.class));
            List<SafetyData> safeties = resp.get("safeties") == null
                    ? List.of()
                    : mapper.convertValue(resp.get("safeties"),
                            mapper.getTypeFactory().constructCollectionType(List.class, SafetyData.class));
            List<DelegationData> delegations = resp.get("delegations") == null
                    ? List.of()
                    : mapper.convertValue(resp.get("delegations"),
                            mapper.getTypeFactory().constructCollectionType(List.class, DelegationData.class));
            return new TraceData(usage, tools == null ? List.of() : tools,
                    recalls == null ? List.of() : recalls,
                    safeties == null ? List.of() : safeties,
                    delegations == null ? List.of() : delegations);
        } catch (Exception e) {
            LoggerFactory.getLogger(AgentClient.class)
                    .warn("[AgentClient] trace 数据解析失败（不影响对话）: {}", e.getMessage());
            return TraceData.empty();
        }
    }

    /**
     * 流式对话：把 agent 的 SSE 逐 token 转发给前端 emitter。
     *
     * agent 事件：
     *   - 默认 event 的 data：token 片段（转发给前端，并累积为完整回复）
     *   - event: tool/rag/usage：trace 事件（原样转发前端，同时累积供回合后落库）
     *   - event: memory 的 data：{"summary":"..."}（不转发，回合后回调落库）
     *   - event: error 的 data：{code,message}（SSE 已提交后的终态业务错误；透传前端，
     *     同时按 error 结局收尾，不触发 onComplete、不落库助手消息）
     *   - data: [DONE]：结束标记（不转发）
     *
     * 阻塞方法，需在工作线程中调用。仅过熔断器（流式不重试）；熔断打开时立即失败。
     *
     * 中断传播：调用方把 {@code bodyRef}（本次请求的 agent 响应体流引用）交进来，
     * 前端断开时通过 {@code bodyRef.get().close()} 关闭该流，让阻塞中的 readLine()
     * 立即抛 IOException 退出，从而真正取消对 agent 的请求（agent 侧同步感知断连）。
     *
     * @param onComplete      流正常结束回调（完整回复文本、最新摘要、trace 累积数据）
     * @param onTerminalError 流以失败/熔断/前端断开结束时回调（调用方据此 best-effort 落取消行）
     * @param bodyRef         由调用方持有、本方法填充的 agent 响应体流引用（取消钩子）
     */
    public void chatStream(AgentChatPayload payload, SseEmitter emitter,
                           StreamCompleteListener onComplete,
                           StreamTerminalErrorListener onTerminalError,
                           AtomicReference<InputStream> bodyRef) {
        StringBuilder fullAnswer = new StringBuilder();
        String[] memorySummary = new String[]{null};
        TraceAccumulator trace = new TraceAccumulator();
        // 仅过熔断器（流式不可重试）；RestClient 会把底层 IOException 包装为 ResourceAccessException（非受检）
        Supplier<Object> streamTask = () -> {
            restClient.post()
                    .uri("/api/agent/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // agent 在流开始前拒绝（4xx/503）：读出 JSON 错误体带上真因，
                            // 否则上层只能看到无信息量的 "错误状态: 400 BAD_REQUEST"
                            String bodyText = readBodyLimited(response.getBody(), 4096);
                            String detail = describeAgentError(bodyText);
                            if (bodyText.contains("SAFETY_BLOCKED")) {
                                // 内容安全闸门拒绝（P4-17）：专用异常，上游补录审计行并给前端干净 400
                                throw new AgentSafetyBlockedException(
                                        "Agent 安全闸门拒绝输入: " + detail);
                            }
                            throw new IllegalStateException("Agent 流式接口返回错误状态: "
                                    + response.getStatusCode() + detail);
                        }
                        // 暴露响应体流：前端断开时由调用方 close 该流以取消本次请求
                        bodyRef.set(response.getBody());
                        // 逐行解析 SSE：空行分隔事件；event: 行声明事件类型，data: 行为数据
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            List<String> dataLines = new ArrayList<>();
                            String eventName = null;
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) {
                                    if (!dataLines.isEmpty()) {
                                        String data = String.join("\n", dataLines);
                                        dataLines.clear();
                                        dispatchEvent(eventName, data, fullAnswer, memorySummary, emitter, trace);
                                        eventName = null;
                                    }
                                    continue;
                                }
                                if (line.startsWith("event:")) {
                                    eventName = line.substring(6).stripLeading();
                                } else if (line.startsWith("data:")) {
                                    dataLines.add(line.substring(5).stripLeading());
                                }
                                // id:/retry:/注释行 忽略
                            }
                        }
                        return null;
                    });
            return null;
        };
        Supplier<Object> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, streamTask);

        try {
            // 计时覆盖整条 SSE 交换（含生成时长；错误路径自动打 outcome=error）
            metrics.runAgentCall("chatStream", guarded::get);
            onComplete.onComplete(fullAnswer.toString(), memorySummary[0], trace.snapshot());
            emitter.complete();
        } catch (CallNotPermittedException e) {
            log.warn("[AgentClient] agent 熔断中，流式请求快速失败");
            safeNotifyError(onTerminalError, e, trace.snapshot());
            emitter.completeWithError(new IllegalStateException("AI 服务暂时不可用（熔断中），请稍后再试"));
        } catch (AgentSafetyBlockedException e) {
            // 内容安全闸门拒绝输入（P4-17）：流未开始，completeWithError 交由全局异常处理返回干净 400；
            // terminal 回调里 ChatController 补录 prompt_safety_record 审计行
            log.warn("[AgentClient] agent 安全闸门拒绝输入: {}", e.getMessage());
            safeNotifyError(onTerminalError, e, trace.snapshot());
            emitter.completeWithError(e);
        } catch (AgentStreamTerminalException e) {
            // agent 的 event:error 已透传前端：按错误记账（不落库助手消息），前端连接正常收尾
            log.warn("[AgentClient] agent 流式终态业务错误: {}", e.getMessage());
            safeNotifyError(onTerminalError, e, trace.snapshot());
            try {
                emitter.complete();
            } catch (Exception completeEx) {
                log.debug("[AgentClient] error 事件后 complete 失败（前端可能已断开）: {}",
                        completeEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("[AgentClient] 流式对话失败: {}", e.getMessage());
            safeNotifyError(onTerminalError, e, trace.snapshot());
            emitter.completeWithError(e);
        }
    }

    private static void safeNotifyError(StreamTerminalErrorListener onTerminalError,
                                        Throwable e, TraceData accumulated) {
        if (onTerminalError != null) {
            try {
                onTerminalError.onTerminalError(e, accumulated);
            } catch (Exception ex) {
                log.warn("[AgentClient] onTerminalError 回调异常（不影响流收尾）: {}", ex.getMessage());
            }
        }
    }

    /** 读取错误响应体（限量，避免异常页面/超长报文撑爆内存与日志）；读失败返回空串 */
    static String readBodyLimited(InputStream in, int maxBytes) {
        if (in == null) {
            return "";
        }
        try {
            byte[] bytes = in.readNBytes(maxBytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把 agent 的 JSON 错误体（{"code":"...","message":"..."}）提炼成可读后缀；
     * 非 JSON/空体返回空串（调用方只保留状态码文案）。
     */
    static String describeAgentError(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return "";
        }
        String body = bodyText.strip();
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            String message = node.path("message").asText("");
            String code = node.path("code").asText("");
            if (message.isBlank()) {
                return "";
            }
            return code.isBlank() ? "：" + message : "：[" + code + "] " + message;
        } catch (Exception e) {
            // 非 JSON：截断原文辅助排查
            return "：" + (body.length() > 500 ? body.substring(0, 500) : body);
        }
    }

    /** 分发一个完整 SSE 事件（包私有以便单测；P2-12） */
    void dispatchEvent(String eventName, String data,
                       StringBuilder fullAnswer, String[] memorySummary,
                       SseEmitter emitter, TraceAccumulator trace) throws IOException {
        if ("memory".equals(eventName)) {
            // 摘要回传事件：解析 JSON 取 summary，不转发给前端
            try {
                JsonNode node = objectMapper.readTree(data);
                JsonNode summary = node.get("summary");
                if (summary != null && !summary.asText().isBlank()) {
                    memorySummary[0] = summary.asText();
                }
            } catch (Exception e) {
                log.warn("[AgentClient] 解析 memory 事件失败: {}", e.getMessage());
            }
            return;
        }
        if ("trace-reset".equals(eventName)) {
            // 主模型失败、备用模型将重放：清空累积，前端面板也要清空
            trace.reset();
            forwardNamedEvent(emitter, "trace-reset", data);
            return;
        }
        if ("approval-request".equals(eventName)) {
            // 高危工具审批请求：原样转发前端弹窗，不落库、不累积（决策走独立短连接）
            forwardNamedEvent(emitter, "approval-request", data);
            return;
        }
        if ("error".equals(eventName)) {
            // agent 在 SSE 已提交后下发的终态错误（如上游模型拒绝图片）：
            // 先原样透传给前端拿到可读原因，再抛标记异常终止交换——
            // 外层据此走 onTerminalError（outcome=error，不 persit 空/半截助手回复），
            // 但前端 emitter 正常 complete（连接健康，不能 completeWithError 把流截断成 network error）
            forwardNamedEvent(emitter, "error", data);
            throw new AgentStreamTerminalException(data);
        }
        if ("safety".equals(eventName)) {
            // 内容安全检测记录（P4-17）：累积供回合末落 prompt_safety_record，同时透传前端
            // （前端对未知命名事件已忽略；保留转发以便后续 UI 加轻量标记）
            SafetyData safety = parseEventData(data, SafetyData.class);
            if (safety != null) {
                trace.safeties.add(safety);
            }
            forwardNamedEvent(emitter, "safety", data);
            return;
        }
        if ("tool".equals(eventName)) {
            ToolData tool = parseEventData(data, ToolData.class);
            if (tool != null) {
                trace.tools.add(tool);
            }
            forwardNamedEvent(emitter, "tool", data);
            return;
        }
        if ("rag".equals(eventName)) {
            RagData rag = parseEventData(data, RagData.class);
            if (rag != null) {
                trace.recalls.add(rag);
            }
            forwardNamedEvent(emitter, "rag", data);
            return;
        }
        if ("delegation".equals(eventName)) {
            // 子代理委派节点（父子 Agent 调用树）：累积供落库/历史回查，同时透传前端实时建树
            DelegationData delegation = parseEventData(data, DelegationData.class);
            if (delegation != null) {
                trace.delegations.add(delegation);
            }
            forwardNamedEvent(emitter, "delegation", data);
            return;
        }
        if ("usage".equals(eventName)) {
            UsageData usage = parseEventData(data, UsageData.class);
            if (usage != null) {
                trace.usage = usage;
                // 费用以后端 billing 配置为准重新计算后随事件转发（agent 不算钱）
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> enriched = objectMapper.convertValue(usage, java.util.Map.class);
                    enriched.put("costYuan", billingService.cost(usage.model(), usage));
                    forwardNamedEvent(emitter, "usage", objectMapper.writeValueAsString(enriched));
                    return;
                } catch (Exception e) {
                    log.warn("[AgentClient] usage 计费富化失败，转发原始事件: {}", e.getMessage());
                }
            }
            forwardNamedEvent(emitter, "usage", data);
            return;
        }
        if ("[DONE]".equals(data)) {
            return;
        }
        // 默认事件：token 片段
        fullAnswer.append(data);
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            // 前端已断开，终止读取（标记异常供调用方区分 outcome=cancelled）
            throw new StreamClientClosedException("前端连接已断开", e);
        }
    }

    /** 原样转发 agent 的命名事件给前端（事件名保持一致） */
    private void forwardNamedEvent(SseEmitter emitter, String eventName, String rawJson) throws IOException {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(rawJson, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // 前端已断开，终止读取（标记异常供调用方区分 outcome=cancelled）
            throw new StreamClientClosedException("前端连接已断开", e);
        }
    }

    private <T> T parseEventData(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[AgentClient] 解析 {} 事件失败: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** SSE trace 事件累积器（单工作线程内使用，无需并发结构）；包私有以便单测（P2-12） */
    static final class TraceAccumulator {
        private final List<ToolData> tools = new ArrayList<>();
        private final List<RagData> recalls = new ArrayList<>();
        private final List<SafetyData> safeties = new ArrayList<>();
        private final List<DelegationData> delegations = new ArrayList<>();
        private volatile UsageData usage;

        /** agent fallback 重放（trace-reset）：清空已累积的首轮数据 */
        private void reset() {
            tools.clear();
            recalls.clear();
            safeties.clear();
            delegations.clear();
            usage = null;
        }

        /** 累积快照（包私有以便单测；P2-12） */
        TraceData snapshot() {
            return new TraceData(usage == null ? UsageData.empty() : usage,
                    List.copyOf(tools), List.copyOf(recalls), List.copyOf(safeties),
                    List.copyOf(delegations));
        }
    }

    /** 获取 agent 侧可用的内置 Tool 名称（best-effort，失败返回空集） */
    public Set<String> listToolNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode node = metrics.timeAgentCall("listTools", () -> restClient.get()
                    .uri("/api/agent/tools")
                    .retrieve()
                    .body(JsonNode.class));
            JsonNode tools = node == null ? null : node.get("tools");
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) {
                    names.add(t.asText());
                }
            }
        } catch (Exception e) {
            log.warn("[AgentClient] 获取 agent Tool 列表失败: {}", e.getMessage());
        }
        return names;
    }

    /** 健康检查：agent 是否可达（不经过熔断器，避免探测本身受熔断状态影响） */
    public boolean isHealthy() {
        try {
            restClient.get().uri("/api/agent/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 高危工具审批（短连接，不经 SSE 熔断器） ====================

    /**
     * 中转用户对高危工具的决策到 agent。
     * agent 404（条目不存在/非本人）/409（已决策）以 HttpClientErrorException 抛出，
     * 由调用方映射 HTTP 状态；成功返回 agent 回执（含 tool/conversationId，供 remember 授权）。
     */
    public Map<String, Object> decideApproval(String approvalId, String userId,
                                              boolean approved, boolean remember) {
        return restClient.post()
                .uri("/api/agent/approvals/{id}/decision", approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "approved", approved, "remember", remember))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
    }

    /** 拉取 agent 内置技能目录（静态 + mcp:* 动态），agent 不可达时返回 null */
    public List<Map<String, Object>> listBuiltinSkills() {
        try {
            Map<String, Object> resp = metrics.timeAgentCall("listBuiltinSkills", () -> restClient.get()
                    .uri("/api/agent/skills/catalog")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    }));
            Object skills = resp == null ? null : resp.get("skills");
            if (skills instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
                return typed;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("[AgentClient] 获取内置技能目录失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== RAG 索引（仅过熔断器，不重试） ====================

    /**
     * 索引一篇文档到 agent 侧向量库（切块 + 本地 embedding）。
     *
     * @return 切块数量
     */
    public int indexDocument(String userId, String docId, String name, String text) {
        Supplier<Integer> supplier = CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> metrics.timeAgentCall("indexDocument", () -> {
                    Map<String, Object> resp = restClient.post()
                            .uri("/api/agent/rag/index-document")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of(
                                    "userId", userId,
                                    "docId", docId,
                                    "name", name == null ? "" : name,
                                    "text", text == null ? "" : text))
                            .retrieve()
                            .body(Map.class);
                    Object chunks = resp == null ? null : resp.get("chunks");
                    return chunks instanceof Number n ? n.intValue() : 0;
                }));
        return supplier.get();
    }

    /**
     * 以 multipart 转发一个二进制文档到 agent 侧索引（PDF/Word/Excel/PPT/HTML，P1-9.2）。
     * agent 负责 Tika 抽正文 + 切块 + 向量化。
     *
     * @param fileName 原始文件名（带后缀，供 agent Tika 探测格式）
     * @return 切块数量
     * @throws org.springframework.web.client.HttpClientErrorException agent 返回 4xx 时抛出
     *         （422=文档不受支持/加密/损坏/正文超限；413=超 20MB；消息体含可读原因），
     *         熔断器已配置忽略 HttpClientErrorException，不会顶开熔断
     */
    public int indexDocumentFile(String userId, String docId, String name,
                                 String fileName, byte[] content) {
        // ByteArrayResource 匿名子类重写 getFilename：multipart 才能带上文件名（Tika 靠后缀探测）
        org.springframework.core.io.ByteArrayResource fileResource =
                new org.springframework.core.io.ByteArrayResource(content == null ? new byte[0] : content) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("userId", userId);
        form.add("docId", docId);
        form.add("name", name == null ? "" : name);
        form.add("file", fileResource);

        Supplier<Integer> supplier = CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> metrics.timeAgentCall("indexDocumentFile", () -> {
                    Map<String, Object> resp = restClient.post()
                            .uri("/api/agent/rag/index-file")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(form)
                            .retrieve()
                            .body(Map.class);
                    Object chunks = resp == null ? null : resp.get("chunks");
                    return chunks instanceof Number n ? n.intValue() : 0;
                }));
        return supplier.get();
    }

    /** 索引一条对话消息到 agent 侧向量库（跨会话长期记忆）；仅过熔断器，异常由调用方 best-effort 处理 */
    public void indexConversation(String userId, String role, String content) {
        Supplier<Object> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> metrics.timeAgentCall("indexConversation", () -> {
                    restClient.post()
                            .uri("/api/agent/rag/index-conversation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of(
                                    "userId", userId,
                                    "role", role,
                                    "content", content == null ? "" : content))
                            .retrieve()
                            .toBodilessEntity();
                    return null;
                }));
        guarded.get();
    }

    /** 删除 agent 侧某文档的全部向量片段（删知识库文档时 best-effort 调用） */
    public void removeDocument(String docId) {
        Supplier<Object> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> metrics.timeAgentCall("removeDocument", () -> {
                    restClient.post()
                            .uri("/api/agent/rag/remove-document")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("docId", docId == null ? "" : docId))
                            .retrieve()
                            .toBodilessEntity();
                    return null;
                }));
        guarded.get();
    }
}
