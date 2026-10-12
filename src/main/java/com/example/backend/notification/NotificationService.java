package com.example.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知服务（P2-9 M2）。
 *
 * - 写点唯一：{@link com.example.backend.task.TaskRunner} 任务成功后写一条；
 *   写失败 best-effort（记日志），绝不能反噬任务执行主链路；
 * - 读链路：游标轮询（after_id），前端 30s（前台）/120s（后台）拉取；
 *   SSE 推送为 M2.5 升级位（同一 payload）。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 单次拉取上限（防异常客户端一次拉爆） */
    private static final int MAX_LIMIT = 50;

    /** 回答摘要截断长度 */
    private static final int BODY_MAX = 120;

    private final UserNotificationRepository repository;

    public NotificationService(UserNotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * 任务执行成功通知（M2 唯一写点）。
     * best-effort：任何异常只记日志，不影响任务执行结果。
     */
    public void notifyTaskResult(String userId, String taskName, String answer, Long conversationId) {
        try {
            UserNotificationEntity notification = new UserNotificationEntity();
            notification.setUserId(userId);
            notification.setType(UserNotificationEntity.TYPE_TASK_RESULT);
            notification.setTitle(truncate("定时任务「" + taskName + "」已完成", 200));
            notification.setBody(summarize(answer));
            notification.setTargetConversationId(conversationId);
            repository.save(notification);
        } catch (Exception e) {
            log.warn("[Notification] 任务结果通知落库失败（不影响任务）: {}", e.getMessage());
        }
    }

    /** 游标拉取（afterId 缺省 0 = 从头；limit 收敛到 [1, 50]） */
    public List<UserNotificationEntity> list(String userId, long afterId, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return repository.findByUserIdAndIdGreaterThanOrderByIdAsc(
                userId, Math.max(afterId, 0L), PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    public long unreadCount(String userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    /** 标记已读（幂等）；非本人/不存在返回 false（controller 404） */
    public boolean markRead(String userId, Long id) {
        UserNotificationEntity notification = repository.findByIdAndUserId(id, userId).orElse(null);
        if (notification == null) {
            return false;
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            repository.save(notification);
        }
        return true;
    }

    /** 回答摘要：折叠空白 + 截断 120 字（纯图片/空回答给兜底文案） */
    private static String summarize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "任务已完成，点击查看结果";
        }
        return truncate(answer.replaceAll("\\s+", " ").trim(), BODY_MAX);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}