package com.example.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Agent 服务 HTTP 客户端
 *
 * 封装对 agent 工程（d:\code\agent，默认 http://localhost:8080）的调用：
 *   POST /api/agent/chat              对话
 *   GET  /api/agent/skills            平台全部 Skill
 *   POST /api/agent/skills/upload     注册 Skill（原始 SkillDefinition JSON）
 *   POST /api/agent/skills/mount      用户挂载 Skill
 *   POST /api/agent/skills/unmount    用户卸载 Skill
 *   POST /api/agent/skills/unregister 注销 Skill（删除自定义技能时）
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentClient(@Value("${agent.service.base-url}") String baseUrl,
                       @Value("${agent.service.connect-timeout:5s}") Duration connectTimeout,
                       @Value("${agent.service.read-timeout:120s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        log.info("[AgentClient] Agent 服务地址: {}", baseUrl);
    }

    /** 对话，返回 Agent 回复文本 */
    public String chat(String userId, String message) {
        Map<String, Object> resp = restClient.post()
                .uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "message", message))
                .retrieve()
                .body(Map.class);
        Object answer = resp == null ? null : resp.get("answer");
        return answer == null ? "" : answer.toString();
    }

    /**
     * 流式对话：把 agent 的 SSE 逐 token 转发给前端 emitter。
     *
     * 阻塞方法，需在工作线程中调用（控制器返回 SseEmitter 后异步执行）。
     *
     * @param onComplete 流正常结束后回调，参数为完整回复文本（用于落库）
     */
    public void chatStream(String userId, String message, SseEmitter emitter, Consumer<String> onComplete) {
        StringBuilder fullAnswer = new StringBuilder();
        try {
            restClient.post()
                    .uri("/api/agent/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", userId, "message", message))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("Agent 流式接口返回错误状态: " + response.getStatusCode());
                        }
                        // 逐行解析 agent 返回的 SSE：空行分隔事件，data: 行为数据
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            List<String> dataLines = new ArrayList<>();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) {
                                    if (!dataLines.isEmpty()) {
                                        String data = String.join("\n", dataLines);
                                        dataLines.clear();
                                        if ("[DONE]".equals(data)) {
                                            continue;
                                        }
                                        fullAnswer.append(data);
                                        try {
                                            emitter.send(SseEmitter.event().data(data));
                                        } catch (IOException e) {
                                            // 前端已断开，终止读取
                                            throw new RuntimeException("前端连接已断开", e);
                                        }
                                    }
                                    continue;
                                }
                                if (line.startsWith("data:")) {
                                    dataLines.add(line.substring(5).stripLeading());
                                }
                            }
                        }
                        return null;
                    });

            onComplete.accept(fullAnswer.toString());
            emitter.complete();
        } catch (Exception e) {
            log.warn("[AgentClient] 流式对话失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /** 获取 agent 侧已注册的全部 Skill 名称 */
    public Set<String> listSkillNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode array = restClient.get()
                    .uri("/api/agent/skills")
                    .retrieve()
                    .body(JsonNode.class);
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    JsonNode name = node.get("name");
                    if (name != null) {
                        names.add(name.asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AgentClient] 获取 agent Skill 列表失败: {}", e.getMessage());
        }
        return names;
    }

    /** 获取 agent 侧可用的内置 Tool 名称 */
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

    /** 上传/注册 Skill（json 为 SkillDefinition 格式的原始 JSON 字符串） */
    public void uploadSkill(String json) {
        restClient.post()
                .uri("/api/agent/skills/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .toBodilessEntity();
    }

    /** 用户挂载 Skill（幂等） */
    public void mount(String userId, String skillName) {
        restClient.post()
                .uri("/api/agent/skills/mount")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "skillName", skillName))
                .retrieve()
                .toBodilessEntity();
    }

    /** 用户卸载 Skill */
    public void unmount(String userId, String skillName) {
        restClient.post()
                .uri("/api/agent/skills/unmount")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "skillName", skillName))
                .retrieve()
                .toBodilessEntity();
    }

    /** 从 agent 注销一个 Skill（删除自定义技能时调用） */
    public void unregister(String skillName) {
        restClient.post()
                .uri("/api/agent/skills/unregister")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("skillName", skillName))
                .retrieve()
                .toBodilessEntity();
    }

    /** 健康检查：agent 是否可达 */
    public boolean isHealthy() {
        try {
            restClient.get().uri("/api/agent/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== RAG 索引 ====================

    /**
     * 索引一篇文档到 agent 侧向量库（切块 + 本地 embedding）。
     *
     * @return 切块数量
     */
    public int indexDocument(String userId, String docId, String name, String text) {
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
    }

    /** 索引一条对话消息到 agent 侧向量库（跨会话长期记忆） */
    public void indexConversation(String userId, String role, String content) {
        restClient.post()
                .uri("/api/agent/rag/index-conversation")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "userId", userId,
                        "role", role,
                        "content", content == null ? "" : content))
                .retrieve()
                .toBodilessEntity();
    }

    /** 删除 agent 侧某文档的全部向量片段（删知识库文档时 best-effort 调用） */
    public void removeDocument(String docId) {
        restClient.post()
                .uri("/api/agent/rag/remove-document")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("docId", docId == null ? "" : docId))
                .retrieve()
                .toBodilessEntity();
    }
}
