package com.example.backend.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 回答反馈（P2-11.1）：用户对某一回合（traceId）助手回答的赞/踩。
 * 唯一键 (trace_id, user_id)：一个用户对一个回合至多一行，再点同一评价=删除（取消）。
 */
@Entity
@Table(name = "message_feedback")
public class MessageFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被反馈回合的 traceId（同回合 user/assistant 消息行共享） */
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    /** 反馈用户（用户名） */
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    /** 所属会话（提交时从消息行冗余，0=默认/历史会话） */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    /** 评价：1=赞，-1=踩 */
    @Column(nullable = false)
    private Integer rating;

    /** 点踩原因码（{@link FeedbackReasons} 白名单）；赞时为 null */
    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    /** 用户补充说明（≤1000） */
    @Column(length = 1000)
    private String comment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
