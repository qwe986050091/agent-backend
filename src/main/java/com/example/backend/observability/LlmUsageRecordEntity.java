package com.example.backend.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每轮对话一行的 LLM token 用量与费用记录（P2-10）。
 */
@Entity
@Table(name = "llm_usage_record")
public class LlmUsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    @Column(length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    @Column(name = "cost_yuan", nullable = false, precision = 12, scale = 6)
    private BigDecimal costYuan = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer rounds = 0;

    @Column(nullable = false)
    private Boolean fallback = false;

    @Column(nullable = false)
    private Boolean cancelled = false;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs = 0L;

    /**
     * 本轮上下文四段预算与实际构成（P1-D，JSON 文本）。
     * MySQL JSON 列，由数据库做合法性校验；NULL = 该回合无 context 事件（老数据/装配失败）。
     */
    @Column(name = "context_json", columnDefinition = "json")
    private String contextJson;

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
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getCostYuan() { return costYuan; }
    public void setCostYuan(BigDecimal costYuan) { this.costYuan = costYuan; }
    public Integer getRounds() { return rounds; }
    public void setRounds(Integer rounds) { this.rounds = rounds; }
    public Boolean getFallback() { return fallback; }
    public void setFallback(Boolean fallback) { this.fallback = fallback; }
    public Boolean getCancelled() { return cancelled; }
    public void setCancelled(Boolean cancelled) { this.cancelled = cancelled; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
