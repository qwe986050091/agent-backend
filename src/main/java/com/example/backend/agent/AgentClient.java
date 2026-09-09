package com.example.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AgentClient(@Value("${agent.service.base-url}") String baseUrl,
                       @Value("${agent.service.connect-timeout:5s}") Duration connectTimeout,
                       @Value("${agent.service.read-timeout:120s}") Duration readTimeout,
                       @Value("${agent.service.http-pool-threads:8}") int poolThreads,
                       @Value("${agent.service.internal-token:dev-internal-token}") String internalToken,
                       CircuitBreaker agentCircuitBreaker,
                       Retry agentRetry) {
        this.circuitBreaker = agentCircuitBreaker;
        this.retry = agentRetry;

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
                // 服务间鉴权：所有请求（含 SSE exchange/RAG）统一携带 X-Agent-Token
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("X-Agent-Token", internalToken);
                    return execution.execute(request, body);
                })
                .build();
        log.info("[AgentClient] Agent 服务地址: {}（无状态协议 + 连接池 + 熔断/重试 + 内部鉴权）", baseUrl);
    }

    // ==================== 对话协议 DTO ====================

    /** 历史消息（与 agent 端 HistoryMessage 对应） */
    public record AgentChatMessage(String role, String content) {}

    /** 对话请求 payload：userId + 消息 + 摘要 + 近期历史 + 已挂载技能定义 */
    public record AgentChatPayload(String userId,
                                   String message,
                                   String summary,
                                   List<AgentChatMessage> history,
                                   List<Map<String, Object>> skills) {}

    /** 同步对话结果：回复文本 + 回合后最新摘要 */
    public record AgentChatResult(String answer, String summary) {}

    /** 同步对话，返回 Agent 回复文本与最新摘要（重试在内、熔断在外；熔断打开时快速失败） */
    public AgentChatResult chat(AgentChatPayload payload) {
        // 装饰顺序：CB(retry(doChat)) —— 重试在一次熔断器统计内进行，最终结果只记一次成功/失败
        Supplier<AgentChatResult> supplier = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, () -> doChat(payload)));
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
        return new AgentChatResult(answer, summary == null ? null : summary.toString());
    }

    /**
     * 流式对话：把 agent 的 SSE 逐 token 转发给前端 emitter。
     *
     * agent 事件：
     *   - 默认 event 的 data：token 片段（转发给前端，并累积为完整回复）
     *   - event: memory 的 data：{"summary":"..."}（不转发，回合后回调落库）
     *   - data: [DONE]：结束标记（不转发）
     *
     * 阻塞方法，需在工作线程中调用。仅过熔断器（流式不重试）；熔断打开时立即失败。
     *
     * 中断传播：调用方把 {@code bodyRef}（本次请求的 agent 响应体流引用）交进来，
     * 前端断开时通过 {@code bodyRef.get().close()} 关闭该流，让阻塞中的 readLine()
     * 立即抛 IOException 退出，从而真正取消对 agent 的请求（agent 侧同步感知断连）。
     *
     * @param onComplete 流正常结束后回调，参数为（完整回复文本，最新摘要）
     * @param bodyRef    由调用方持有、本方法填充的 agent 响应体流引用（取消钩子）
     */
    public void chatStream(AgentChatPayload payload, SseEmitter emitter,
                           BiConsumer<String, String> onComplete,
                           AtomicReference<InputStream> bodyRef) {
        StringBuilder fullAnswer = new StringBuilder();
        String[] memorySummary = new String[]{null};
        // 仅过熔断器（流式不可重试）；RestClient 会把底层 IOException 包装为 ResourceAccessException（非受检）
        Supplier<Object> streamTask = () -> {
            restClient.post()
                    .uri("/api/agent/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("Agent 流式接口返回错误状态: " + response.getStatusCode());
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
                                        dispatchEvent(eventName, data, fullAnswer, memorySummary, emitter);
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
            guarded.get();
            onComplete.accept(fullAnswer.toString(), memorySummary[0]);
            emitter.complete();
        } catch (CallNotPermittedException e) {
            log.warn("[AgentClient] agent 熔断中，流式请求快速失败");
            emitter.completeWithError(new IllegalStateException("AI 服务暂时不可用（熔断中），请稍后再试"));
        } catch (Exception e) {
            log.warn("[AgentClient] 流式对话失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /** 分发一个完整 SSE 事件 */
    private void dispatchEvent(String eventName, String data,
                               StringBuilder fullAnswer, String[] memorySummary,
                               SseEmitter emitter) throws IOException {
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
        if ("[DONE]".equals(data)) {
            return;
        }
        // 默认事件：token 片段
        fullAnswer.append(data);
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            // 前端已断开，终止读取
            throw new RuntimeException("前端连接已断开", e);
        }
    }

    /** 获取 agent 侧可用的内置 Tool 名称（best-effort，失败返回空集） */
    public Set<String> listToolNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode node = restClient.get()
                    .uri("/api/agent/tools")
                    .retrieve()
                    .body(JsonNode.class);
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

    // ==================== RAG 索引（仅过熔断器，不重试） ====================

    /**
     * 索引一篇文档到 agent 侧向量库（切块 + 本地 embedding）。
     *
     * @return 切块数量
     */
    public int indexDocument(String userId, String docId, String name, String text) {
        Supplier<Integer> supplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
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
        });
        return supplier.get();
    }

    /** 索引一条对话消息到 agent 侧向量库（跨会话长期记忆）；仅过熔断器，异常由调用方 best-effort 处理 */
    public void indexConversation(String userId, String role, String content) {
        Supplier<Object> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
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
        });
        guarded.get();
    }

    /** 删除 agent 侧某文档的全部向量片段（删知识库文档时 best-effort 调用） */
    public void removeDocument(String docId) {
        Supplier<Object> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
            restClient.post()
                    .uri("/api/agent/rag/remove-document")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("docId", docId == null ? "" : docId))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
        guarded.get();
    }
}
