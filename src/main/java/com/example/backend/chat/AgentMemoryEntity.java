package com.example.backend.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Agent 对话记忆摘要（每个用户一条）。
 *
 * agent 无状态化后，会话摘要由 agent 侧 LLM 压缩生成、通过 SSE memory 事件/同步响应回传，
 * backend 持久化到本表；每次对话请求再随 payload 下发给 agent 重建请求级记忆。
 */
@Entity
@Table(name = "agent_memory")
public class AgentMemoryEntity {

    /** 所属用户（用户名），主键 */
    @Id
    @Column(name = "user_id", length = 100)
    private String userId;

    /** 会话历史摘要纯文本 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentMemoryEntity() {}

    public AgentMemoryEntity(String userId, String summary) {
        this.userId = userId;
        this.summary = summary;
        this.updatedAt = LocalDateTime.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
