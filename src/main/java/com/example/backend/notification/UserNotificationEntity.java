package com.example.backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 站内通知事件（P2-9 M2）：一行 = 一条待触达用户的事件。
 *
 * v1 写点唯一（TaskRunner 成功执行后），{@code type} 预留后续复用
 * （审批提醒、长任务完成等）；读链路为游标轮询，离线期间事件不丢。
 */
@Entity
@Table(name = "user_notification")
public class UserNotificationEntity {

    /** 事件类型：定时任务结果（v1 唯一） */
    public static final String TYPE_TASK_RESULT = "task_result";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 30)
    private String type = TYPE_TASK_RESULT;

    @Column(nullable = false, length = 200)
    private String title;

    /** 回答摘要（截断 120 字） */
    @Column(length = 500)
    private String body;

    /** 点击跳转目标会话（任务专属会话） */
    @Column(name = "target_conversation_id")
    private Long targetConversationId;

    /** 空=未读 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Long getTargetConversationId() { return targetConversationId; }
    public void setTargetConversationId(Long targetConversationId) { this.targetConversationId = targetConversationId; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}