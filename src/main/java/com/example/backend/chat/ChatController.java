package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话接口（需登录）
 *
 *   POST /api/chat          发送消息（同步，HTTP 转发给 agent 工程），对话记录落库
 *   POST /api/chat/stream   流式对话（SSE 逐 token），完成后落库
 *   GET  /api/chat/history  当前用户的历史消息
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** SSE 流式对话 / RAG 异步索引的工作线程池（HTTP 转发为阻塞调用） */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-sse-stream");
        t.setDaemon(true);
        return t;
    });

    private final AgentClient agentClient;
    private final ChatMessageRepository chatMessageRepository;

    public ChatController(AgentClient agentClient, ChatMessageRepository chatMessageRepository) {
        this.agentClient = agentClient;
        this.chatMessageRepository = chatMessageRepository;
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

        // 1. 保存用户消息
        saveMessage(user.getUsername(), "user", message);

        // 2. HTTP 调用 agent 工程
        String answer;
        try {
            answer = agentClient.chat(user.getUsername(), message);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 服务调用失败，请确认 agent 工程已启动: " + e.getMessage(), e);
        }

        // 3. 保存助手回复
        saveMessage(user.getUsername(), "assistant", answer);

        return ResponseEntity.ok(Map.of("answer", answer));
    }

    /**
     * 流式对话（SSE）：逐 token 转发给前端，流结束后把完整回复落库
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody Map<String, String> body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String message = body == null ? null : body.get("message");

        // 不超时（0），长回复可能持续较久
        SseEmitter emitter = new SseEmitter(0L);

        if (message == null || message.trim().isEmpty()) {
            emitter.completeWithError(new IllegalArgumentException("消息内容不能为空"));
            return emitter;
        }

        String userId = user.getUsername();
        // 用户消息先落库
        saveMessage(userId, "user", message);

        streamExecutor.execute(() -> agentClient.chatStream(
                userId, message, emitter,
                // 流正常结束：完整回复落库
                fullAnswer -> saveMessage(userId, "assistant", fullAnswer)));

        return emitter;
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        List<ChatMessageEntity> messages =
                chatMessageRepository.findByUserIdOrderByCreatedAtAsc(user.getUsername());

        List<Map<String, Object>> list = messages.stream().map(m -> {
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("messages", list));
    }

    private void saveMessage(String userId, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(userId);
        entity.setRole(role);
        entity.setContent(content);
        chatMessageRepository.save(entity);

        // 异步写入 RAG 长期记忆（向量索引）；失败只记日志，不影响聊天主流程
        indexConversationAsync(userId, role, content);
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
