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
 * 工具调用审计记录（P2-10）：一次工具调用一行。
 */
@Entity
@Table(name = "tool_call_record")
public class ToolCallRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    @Column(name = "round_seq", nullable = false)
    private Integer roundSeq = 0;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String arguments;

    @Column(name = "result_preview", length = 1000)
    private String resultPreview;

    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs = 0L;

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
    public Integer getRoundSeq() { return roundSeq; }
    public void setRoundSeq(Integer roundSeq) { this.roundSeq = roundSeq; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public String getResultPreview() { return resultPreview; }
    public void setResultPreview(String resultPreview) { this.resultPreview = resultPreview; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
