package com.example.backend.notification;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知接口（P2-9 M2，需登录）
 *
 *   GET  /api/notifications?after_id=0&limit=20  游标拉取（升序；离线后可从上次位置补齐）
 *   GET  /api/notifications/unread-count         未读数
 *   POST /api/notifications/{id}/read            标记已读（幂等）
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamService streamService;

    public NotificationController(NotificationService notificationService,
                                  NotificationStreamService streamService) {
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    /**
     * 实时通知流（P1-E，SSE）：连接建立后服务端主动推送新通知，
     * 前端保留游标轮询作为断线/离线的补齐兜底。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        return streamService.subscribe(currentUser(request));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "after_id", defaultValue = "0") long afterId,
                                  @RequestParam(defaultValue = "20") int limit,
                                  HttpServletRequest request) {
        String userId = currentUser(request);
        List<Map<String, Object>> notifications = notificationService.list(userId, afterId, limit)
                .stream().map(NotificationController::toMap).toList();
        return ResponseEntity.ok(Map.of("notifications", notifications));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of("unread", notificationService.unreadCount(currentUser(request))));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id, HttpServletRequest request) {
        boolean ok = notificationService.markRead(currentUser(request), id);
        return ok
                ? ResponseEntity.ok(Map.of("message", "已读"))
                : ResponseEntity.notFound().build();
    }

    private static String currentUser(HttpServletRequest request) {
        return ((UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).getUsername();
    }

    private static Map<String, Object> toMap(UserNotificationEntity n) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", n.getId());
        m.put("type", n.getType());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("targetConversationId", n.getTargetConversationId());
        m.put("read", n.getReadAt() != null);
        m.put("createdAt", n.getCreatedAt());
        return m;
    }
}