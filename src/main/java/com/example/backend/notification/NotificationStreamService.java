package com.example.backend.notification;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 站内通知实时推送（P1-E）：按用户维护 SSE 连接，事件到达即推。
 *
 * 与聊天 SSE 的区别：通知是「服务端主动、低频、单用户多端」，
 * 因此用独立的长连接接口 + 心跳保活；前端保留游标轮询作为离线补齐兜底。
 */
@Service
public class NotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);

    /** SSE 超时（30 分钟；前端断线后自动重连） */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** 单用户最多并行连接数（防异常客户端开满连接） */
    private static final int MAX_PER_USER = 5;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notify-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public NotificationStreamService() {
        // 心跳：SSE 长连接在 Nginx 60s 空闲会断，25s 发一次注释行保活
        heartbeat.scheduleAtFixedRate(() -> {
            emitters.forEach((userId, list) -> list.forEach(emitter -> {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    }
                } catch (Exception e) {
                    remove(userId, emitter);
                }
            }));
        }, 25, 25, TimeUnit.SECONDS);
    }

    /** 建立一条用户级订阅 */
    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_PER_USER) {
            // 超出上限：踢掉最旧的一条（通常来自异常客户端未触发完成回调）
            SseEmitter oldest = list.get(0);
            list.remove(oldest);
            try {
                oldest.complete();
            } catch (Exception ignore) {
                // 已断开
            }
        }
        list.add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            // 立即回一个 ready，让前端确认连接建立（避免代理层把空响应当失败）
            emitter.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            remove(userId, emitter);
        }
        log.debug("[Notification] 用户 {} 订阅通知流，当前连接 {}", userId, list.size());
        return emitter;
    }

    /** 推送一条已落库通知；离线用户无连接时静默跳过（由游标接口补齐） */
    public void push(String userId, UserNotificationEntity notification) {
        if (userId == null || notification == null) {
            return;
        }
        List<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> data = Map.of(
                "id", notification.getId(),
                "type", notification.getType(),
                "title", notification.getTitle(),
                "body", notification.getBody() == null ? "" : notification.getBody(),
                "targetConversationId", notification.getTargetConversationId() == null
                        ? 0L : notification.getTargetConversationId());
        for (SseEmitter emitter : list) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("notification").data(data,
                            MediaType.APPLICATION_JSON));
                }
            } catch (Exception e) {
                remove(userId, emitter);
            }
        }
    }

    /** 在线连接数（健康检查/排查用） */
    public int onlineUsers() {
        return emitters.size();
    }

    private void remove(String userId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            emitters.remove(userId);
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        emitters.forEach((userId, list) -> list.forEach(e -> {
            try {
                e.complete();
            } catch (Exception ignore) {
                // 已断开
            }
        }));
        emitters.clear();
    }
}
