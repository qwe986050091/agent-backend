package com.example.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 定时任务执行记录（P2-9 执行态管理）：一行 = 一次投递/执行。
 * status 状态机：running → success | failed | skipped_budget（不补跑）。
 */
@Entity
@Table(name = "scheduled_task_run")
public class ScheduledTaskRunEntity {

    /** 执行中（收尾前） */
    public static final String STATUS_RUNNING = "running";
    /** 成功（agent 正常返回并落库） */
    public static final String STATUS_SUCCESS = "success";
    /** 失败（agent 不可用/超时/异常） */
    public static final String STATUS_FAILED = "failed";
    /** 日预算超限跳过（未发 agent 调用） */
    public static final String STATUS_SKIPPED_BUDGET = "skipped_budget";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 20)
    private String status;

    /** 直通现有 trace 审计（usage/tool/rag 全链路可回查） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 结果消息 id（chat_message.id，跳转会话用） */
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

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
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}