package com.example.backend.chat;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.feedback.FeedbackService;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多会话管理接口（需登录）
 *
 *   GET    /api/conversations             会话列表（含 id=0 的"默认会话"虚拟条目，若存在历史遗留消息）
 *   POST   /api/conversations             新建会话 {title?}
 *   PATCH  /api/conversations/{id}        重命名 {title}
 *   DELETE /api/conversations/{id}        删除会话（级联删除其全部消息与摘要）
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    /** 默认/历史会话的保留 id（多会话功能上线前的遗留消息归于此） */
    public static final long DEFAULT_CONVERSATION_ID = 0L;
    /** 默认会话展示名 */
    private static final String DEFAULT_CONVERSATION_TITLE = "默认会话";

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentMemoryRepository agentMemoryRepository;
    private final FeedbackService feedbackService;

    public ConversationController(ConversationRepository conversationRepository,
                                  ChatMessageRepository chatMessageRepository,
                                  AgentMemoryRepository agentMemoryRepository,
                                  FeedbackService feedbackService) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.agentMemoryRepository = agentMemoryRepository;
        this.feedbackService = feedbackService;
    }

    /** 会话列表（最近活跃在前；存在历史遗留消息时追加 id=0 默认会话条目） */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        String userId = currentUser(request);
        List<ConversationEntity> convs = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        List<Map<String, Object>> list = new ArrayList<>();
        for (ConversationEntity c : convs) {
            list.add(toMap(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()));
        }
        // 默认/历史会话（多会话上线前的消息）置顶展示
        if (chatMessageRepository.existsByUserIdAndConversationId(userId, DEFAULT_CONVERSATION_ID)) {
            list.add(0, toMap(DEFAULT_CONVERSATION_ID, DEFAULT_CONVERSATION_TITLE, null, null));
        }
        return ResponseEntity.ok(Map.of("conversations", list));
    }

    /** 新建会话（标题可空，默认"新对话"） */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
        String userId = currentUser(request);
        String title = body == null ? null : body.get("title");
        ConversationEntity conv = new ConversationEntity();
        conv.setUserId(userId);
        conv.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        conversationRepository.save(conv);
        return ResponseEntity.ok(toMap(conv.getId(), conv.getTitle(), conv.getCreatedAt(), conv.getUpdatedAt()));
    }

    /** 重命名会话 */
    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        String userId = currentUser(request);
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "标题不能为空"));
        }
        ConversationEntity conv = conversationRepository.findById(id).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        conv.setTitle(title.trim());
        conversationRepository.save(conv);
        return ResponseEntity.ok(toMap(conv.getId(), conv.getTitle(), conv.getCreatedAt(), conv.getUpdatedAt()));
    }

    /** 删除会话（级联删除其全部消息与记忆摘要） */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        String userId = currentUser(request);
        ConversationEntity conv = conversationRepository.findById(id).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        chatMessageRepository.deleteByUserIdAndConversationId(userId, id);
        agentMemoryRepository.deleteById(new AgentMemoryId(userId, id));
        feedbackService.deleteForConversation(userId, id);
        conversationRepository.delete(conv);
        log.info("[Conversation] 用户 {} 删除会话 {}（{}）", userId, id, conv.getTitle());
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    private static String currentUser(HttpServletRequest request) {
        return ((UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).getUsername();
    }

    private static Map<String, Object> toMap(Long id, String title, Object createdAt, Object updatedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }
}
