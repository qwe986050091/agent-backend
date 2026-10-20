package com.example.backend.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 子代理委派审计记录（P1-C）：一次委派一行，按 parent_delegation_id 还原调用树。
 */
@Entity
@Table(name = "agent_delegation_record")
public class AgentDelegationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    /** 委派节点 id（与 agent 侧 DelegationTraceEvent.id 一致） */
    @Column(name = "delegation_id", nullable = false, length = 32)
    private String delegationId;

    /** 子代理技能名；计划编排为 planner */
    @Column(name = "agent_name", nullable = false, length = 100)
    private String agentName;

    /** 父委派节点 id：NULL=主 Agent 直接发起 */
    @Column(name = "parent_delegation_id", length = 32)
    private String parentDelegationId;

    @Column(name = "task_preview", length = 1000)
    private String taskPreview;

    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs = 0L;

    /** 本次委派消耗 token（子代理 LLM 用量；无法测量为 0） */
    @Column(nullable = false)
    private Integer tokens = 0;

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
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getDelegationId() { return delegationId; }
    public void setDelegationId(String delegationId) { this.delegationId = delegationId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getParentDelegationId() { return parentDelegationId; }
    public void setParentDelegationId(String parentDelegationId) { this.parentDelegationId = parentDelegationId; }
    public String getTaskPreview() { return taskPreview; }
    public void setTaskPreview(String taskPreview) { this.taskPreview = taskPreview; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
