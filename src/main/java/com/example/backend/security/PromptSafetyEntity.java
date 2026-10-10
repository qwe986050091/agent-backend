package com.example.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 内容安全检测记录（P4-17）：一行 = 一次检测事件，一回合可多行（input/rag/tool_output/output 各通道）。
 *
 * @see com.example.backend.observability.TracePersistenceService 写入方
 */
@Entity
@Table(name = "prompt_safety_record")
public class PromptSafetyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId = "";

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    /** 检测通道：input/rag/tool_output/output/outbound_http */
    @Column(nullable = false, length = 20)
    private String channel;

    /** 命中规则明细 JSON 文本：[{ruleId,category,weight}]（MySQL JSON 列，由数据库做合法性校验） */
    @Column(name = "rule_hits", columnDefinition = "json")
    private String ruleHits;

    @Column(nullable = false)
    private Integer score = 0;

    @Column(nullable = false, length = 20)
    private String verdict = "benign";

    @Column(nullable = false, length = 20)
    private String detector = "rule";

    @Column(nullable = false, length = 20)
    private String action = "none";

    @Column(name = "llm_reason", length = 500)
    private String llmReason;

    @Column(length = 500)
    private String excerpt;

    @Column(name = "latency_ms")
    private Integer latencyMs;

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
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRuleHits() { return ruleHits; }
    public void setRuleHits(String ruleHits) { this.ruleHits = ruleHits; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getDetector() { return detector; }
    public void setDetector(String detector) { this.detector = detector; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getLlmReason() { return llmReason; }
    public void setLlmReason(String llmReason) { this.llmReason = llmReason; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}