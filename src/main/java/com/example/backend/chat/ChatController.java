package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话接口（需登录）
 *
 *   POST /api/chat          发送消息（HTTP 转发给 agent 工程），对话记录落库
 *   GET  /api/chat/history  当前用户的历史消息
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

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
    }
}
