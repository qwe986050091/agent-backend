package com.example.backend.task;

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
 * 定时任务定义（P2-9）。
 *
 * next_run_at 承担三职：列表展示「下次执行」、poller 认领凭据（DB CAS）、停机恢复依据。
 */
@Entity
@Table(name = "scheduled_task")
public class ScheduledTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户（用户名） */
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    /** 任务名（专属会话标题 = "⏰ {name}"） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 任务指令（执行时自动前缀注入当前时间） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    /** Spring 6 段 cron（秒 分 时 日 月 周；时区见 app.task.zone） */
    @Column(name = "cron_expr", nullable = false, length = 100)
    private String cronExpr;

    /** 启用开关 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 任务级预授权工具 JSON 数组文本（并入 allowedTools，创建时已向用户明示风险） */
    @Column(name = "tools_whitelist", columnDefinition = "json")
    private String toolsWhitelist;

    /** 任务专属会话（懒创建；用户手动删除后下次执行自动重建） */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 下次执行时间（Asia/Shanghai 墙钟；停用为 null） */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

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
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToolsWhitelist() { return toolsWhitelist; }
    public void setToolsWhitelist(String toolsWhitelist) { this.toolsWhitelist = toolsWhitelist; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}