package com.example.backend.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Agent 对话记忆摘要（每个用户每个会话一条）。
 *
 * agent 无状态化后，会话摘要由 agent 侧 LLM 压缩生成、通过 SSE memory 事件/同步响应回传，
 * backend 持久化到本表；每次对话请求再随 payload 下发给 agent 重建请求级记忆。
 * 主键 (user_id, conversation_id)：多会话下摘要按会话隔离（conversation_id=0 为默认/历史会话）。
 */
@Entity
@Table(name = "agent_memory")
@IdClass(AgentMemoryId.class)
public class AgentMemoryEntity {

    /** 所属用户（用户名） */
    @Id
    @Column(name = "user_id", length = 100)
    private String userId;

    /** 所属会话（0=默认/历史会话） */
    @Id
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 会话历史摘要纯文本 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentMemoryEntity() {}

    public AgentMemoryEntity(String userId, Long conversationId, String summary) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.summary = summary;
        this.updatedAt = LocalDateTime.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
