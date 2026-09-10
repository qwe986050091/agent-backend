package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.skill.SkillEntity;
import com.example.backend.skill.SkillRepository;
import com.example.backend.skill.UserSkillEntity;
import com.example.backend.skill.UserSkillRepository;
import com.example.backend.user.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话接口（需登录）
 *
 *   POST /api/chat          发送消息（同步，HTTP 转发给 agent 工程），对话记录落库
 *   POST /api/chat/stream   流式对话（SSE 逐 token），完成后落库
 *   GET  /api/chat/history  当前用户的历史消息
 *
 * <b>无状态化：</b>agent 不保存跨请求状态。每次调用前由本控制器从 MySQL 组装
 * 对话状态（摘要 agent_memory 表 + 最近 30 条历史 chat_message 表 + 已挂载技能定义
 * skill/user_skill 表）随 payload 下发；agent 回合结束回传最新摘要，本控制器落库。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 下发给 agent 重建记忆的最近历史消息条数（agent 侧压缩阈值 40 / 保留 20） */
    private static final int HISTORY_LIMIT = 30;

    /** SSE 单次对话最长时间（5 分钟），超时主动结束，避免连接/线程被无限占用 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * SSE 流式对话 / RAG 异步索引的工作线程池（HTTP 转发为阻塞调用）。
     * 有界：core 8 / max 32 / 队列 200，满载后 AbortPolicy 拒绝（返回 503，快速失败而非无限堆积线程）。
     */
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            8, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "chat-sse-stream-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdown();
    }

    private final AgentClient agentClient;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentMemoryRepository agentMemoryRepository;
    private final ConversationRepository conversationRepository;
    private final UserSkillRepository userSkillRepository;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    public ChatController(AgentClient agentClient,
                          ChatMessageRepository chatMessageRepository,
                          AgentMemoryRepository agentMemoryRepository,
                          ConversationRepository conversationRepository,
                          UserSkillRepository userSkillRepository,
                          SkillRepository skillRepository,
                          ObjectMapper objectMapper) {
        this.agentClient = agentClient;
        this.chatMessageRepository = chatMessageRepository;
        this.agentMemoryRepository = agentMemoryRepository;
        this.conversationRepository = conversationRepository;
        this.userSkillRepository = userSkillRepository;
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper;
    }

    /** 解析请求中的会话 id（缺省 0 = 默认/历史会话） */
    private static Long conversationIdOf(Map<String, String> body) {
        if (body == null) {
            return ConversationController.DEFAULT_CONVERSATION_ID;
        }
        try {
            String raw = body.get("conversationId");
            return raw == null || raw.isBlank() ? ConversationController.DEFAULT_CONVERSATION_ID : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return ConversationController.DEFAULT_CONVERSATION_ID;
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> chat(HttpServletRequest request, @RequestBody Map<String, String> body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String message = body == null ? null : body.get("message");

        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "EMPTY_MESSAGE", "message", "消息内容不能为空"));
        }

        String userId = user.getUsername();
        Long conversationId = conversationIdOf(body);

        // 1. 先组装状态（必须在保存当前消息之前，否则当前消息会被当成历史重复下发）
        AgentClient.AgentChatPayload payload = buildPayload(userId, conversationId, message);

        // 2. 保存用户消息
        saveMessage(userId, conversationId, "user", message);

        // 3. HTTP 调用 agent 工程（含重试/熔断；失败或熔断中返回 503 友好降级）
        AgentClient.AgentChatResult result;
        try {
            result = agentClient.chat(payload);
        } catch (Exception e) {
            log.warn("[Chat] agent 调用失败，返回降级响应: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "AGENT_UNAVAILABLE",
                            "message", "AI 服务暂时不可用（" + e.getMessage() + "），请稍后重试"));
        }

        // 4. 保存助手回复 + 回写最新摘要
        saveMessage(userId, conversationId, "assistant", result.answer());
        saveSummary(userId, conversationId, result.summary());
        touchConversation(userId, conversationId);

        return ResponseEntity.ok(Map.of("answer", result.answer()));
    }

    /**
     * 流式对话（SSE）：逐 token 转发给前端，流结束后把完整回复与最新摘要落库
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody Map<String, String> body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String message = body == null ? null : body.get("message");

        // 5 分钟超时：超长回复/僵死连接不会无限占用线程与连接
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // agent 响应体流引用（取消钩子）：前端断开/超时时 close 该流，主动终止对 agent 的请求
        AtomicReference<InputStream> agentBodyRef = new AtomicReference<>();
        emitter.onTimeout(() -> {
            log.warn("[Chat] SSE 超过 {}ms 未完成，主动断开并取消 agent 请求", SSE_TIMEOUT_MS);
            closeAgentStream(agentBodyRef);
            emitter.completeWithError(new TimeoutException("响应超时，请重试"));
        });
        emitter.onError(e -> {
            // 前端断开/发送失败：主动取消对 agent 的流式请求（agent 侧随即中断 LLM 生成）
            log.info("[Chat] 前端连接断开，取消 agent 流式请求");
            closeAgentStream(agentBodyRef);
        });
        emitter.onCompletion(() -> {
            // 流正常结束（agentBodyRef 已由 AgentClient 关闭）；兜底再关一次，确保无残留
            closeAgentStream(agentBodyRef);
        });

        if (message == null || message.trim().isEmpty()) {
            emitter.completeWithError(new IllegalArgumentException("消息内容不能为空"));
            return emitter;
        }

        String userId = user.getUsername();
        Long conversationId = conversationIdOf(body);

        // 1. 先组装状态（在保存当前消息之前）
        AgentClient.AgentChatPayload payload = buildPayload(userId, conversationId, message);

        // 2. 用户消息先落库
        saveMessage(userId, conversationId, "user", message);

        try {
            streamExecutor.execute(() -> agentClient.chatStream(
                    payload, emitter,
                    // 流正常结束：完整回复 + 最新摘要落库
                    (fullAnswer, summary) -> {
                        saveMessage(userId, conversationId, "assistant", fullAnswer);
                        saveSummary(userId, conversationId, summary);
                        touchConversation(userId, conversationId);
                    },
                    agentBodyRef));
        } catch (RejectedExecutionException e) {
            // 线程池/队列已满：快速失败，不排队堆积
            log.warn("[Chat] 流式线程池已满，拒绝请求");
            emitter.completeWithError(new IllegalStateException("当前对话繁忙，请稍后重试"));
        }

        return emitter;
    }

    /** 关闭正在进行的 agent 流式请求响应流（前端断开/超时取消钩子），未开始或已结束则空操作 */
    private static void closeAgentStream(AtomicReference<InputStream> bodyRef) {
        InputStream in = bodyRef.get();
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 已断开则忽略
            }
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request, @RequestParam(defaultValue = "0") Long conversationId) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        List<ChatMessageEntity> messages =
                chatMessageRepository.findByUserIdAndConversationIdOrderByCreatedAtAsc(
                        user.getUsername(), conversationId);

        List<Map<String, Object>> list = messages.stream().map(m -> {
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("messages", list));
    }

    // ==================== 无状态 payload 组装 ====================

    /**
     * 从 MySQL 组装 agent 对话请求：会话摘要 + 最近历史（升序）+ 已挂载技能定义。
     * 摘要与历史均按 (userId, conversationId) 隔离，保证多会话互不串上下文。
     */
    private AgentClient.AgentChatPayload buildPayload(String userId, Long conversationId, String message) {
        String summary = agentMemoryRepository.findById(new AgentMemoryId(userId, conversationId))
                .map(AgentMemoryEntity::getSummary)
                .orElse(null);

        List<AgentClient.AgentChatMessage> history = recentHistory(userId, conversationId);
        List<Map<String, Object>> skills = mountedSkillDefs(userId);

        return new AgentClient.AgentChatPayload(userId, conversationId, message, summary, history, skills);
    }

    /** 最近 {@link #HISTORY_LIMIT} 条会话历史消息（反转为时间升序） */
    private List<AgentClient.AgentChatMessage> recentHistory(String userId, Long conversationId) {
        List<ChatMessageEntity> recentDesc =
                chatMessageRepository.findTop30ByUserIdAndConversationIdOrderByIdDesc(userId, conversationId);
        if (recentDesc.isEmpty()) {
            return List.of();
        }
        List<ChatMessageEntity> asc = new ArrayList<>(recentDesc);
        Collections.reverse(asc);
        return asc.stream()
                .map(m -> new AgentClient.AgentChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    /** 该用户已挂载技能的完整定义（与 agent 端 SkillDefinition 字段对齐） */
    private List<Map<String, Object>> mountedSkillDefs(String userId) {
        List<UserSkillEntity> mounts = userSkillRepository.findByUserId(userId);
        List<Map<String, Object>> defs = new ArrayList<>();
        for (UserSkillEntity mount : mounts) {
            SkillEntity skill = skillRepository.findByName(mount.getSkillName()).orElse(null);
            if (skill == null) {
                // 挂载关系指向已删除的技能，跳过
                continue;
            }
            Map<String, Object> def = new HashMap<>();
            def.put("name", skill.getName());
            def.put("shortDescription", skill.getShortDescription());
            def.put("detailedDescription", skill.getDetailedDescription());
            def.put("systemPrompt", skill.getSystemPrompt());
            def.put("toolNames", parseToolNames(skill.getToolNamesJson()));
            defs.add(def);
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseToolNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 落库 ====================

    private void saveMessage(String userId, Long conversationId, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setContent(content);
        chatMessageRepository.save(entity);

        // 异步写入 RAG 长期记忆（向量索引，跨会话）；失败只记日志，不影响聊天主流程
        indexConversationAsync(userId, role, content);
    }

    /** 回写 agent 回传的最新摘要（按会话；无摘要则跳过）；失败不影响聊天主流程 */
    private void saveSummary(String userId, Long conversationId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        try {
            AgentMemoryId id = new AgentMemoryId(userId, conversationId);
            AgentMemoryEntity entity = agentMemoryRepository.findById(id)
                    .orElseGet(() -> new AgentMemoryEntity(userId, conversationId, summary));
            entity.setSummary(summary);
            entity.setUpdatedAt(LocalDateTime.now());
            agentMemoryRepository.save(entity);
        } catch (Exception e) {
            log.warn("[Chat] 记忆摘要落库失败（不影响聊天）: {}", e.getMessage());
        }
    }

    /** 对话后刷新会话的最近活跃时间（默认会话 0 不建表记录，跳过） */
    private void touchConversation(String userId, Long conversationId) {
        if (conversationId == null || conversationId == ConversationController.DEFAULT_CONVERSATION_ID) {
            return;
        }
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            if (conv.getUserId().equals(userId)) {
                conv.setUpdatedAt(LocalDateTime.now());
                conversationRepository.save(conv);
            }
        });
    }

    /** 异步把一条对话索引到 agent 侧向量库（RAG 长期记忆），异常不抛出 */
    private void indexConversationAsync(String userId, String role, String content) {
        streamExecutor.execute(() -> {
            try {
                agentClient.indexConversation(userId, role, content);
            } catch (Exception e) {
                log.warn("[Chat] 对话 RAG 索引失败（不影响聊天）: {}", e.getMessage());
            }
        });
    }
}
