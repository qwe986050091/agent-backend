package com.example.backend.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次 Agent 评测运行（P0-B）：聚合指标 + 元信息。
 * 表结构由 schema.sql 创建（eval_run），Hibernate 只做映射。
 */
@Entity
@Table(name = "eval_run")
public class EvalRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(length = 100)
    private String label;

    @Column(name = "dataset_name", length = 200)
    private String datasetName;

    @Column(length = 50)
    private String mode;

    @Column(length = 100)
    private String model;

    @Column(name = "judge_enabled", nullable = false)
    private Boolean judgeEnabled = false;

    @Column(nullable = false)
    private Integer total = 0;

    @Column(nullable = false)
    private Integer passed = 0;

    @Column(nullable = false)
    private Integer failed = 0;

    @Column(name = "pass_rate", nullable = false)
    private Double passRate = 0d;

    @Column(name = "tool_accuracy")
    private Double toolAccuracy;

    @Column(name = "citation_accuracy")
    private Double citationAccuracy;

    @Column(name = "point_coverage")
    private Double pointCoverage;

    @Column(name = "refusal_rate")
    private Double refusalRate;

    @Column(name = "avg_tokens", nullable = false)
    private Double avgTokens = 0d;

    @Column(name = "avg_duration_ms", nullable = false)
    private Double avgDurationMs = 0d;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

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
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Boolean getJudgeEnabled() { return judgeEnabled; }
    public void setJudgeEnabled(Boolean judgeEnabled) { this.judgeEnabled = judgeEnabled; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Integer getPassed() { return passed; }
    public void setPassed(Integer passed) { this.passed = passed; }
    public Integer getFailed() { return failed; }
    public void setFailed(Integer failed) { this.failed = failed; }
    public Double getPassRate() { return passRate; }
    public void setPassRate(Double passRate) { this.passRate = passRate; }
    public Double getToolAccuracy() { return toolAccuracy; }
    public void setToolAccuracy(Double toolAccuracy) { this.toolAccuracy = toolAccuracy; }
    public Double getCitationAccuracy() { return citationAccuracy; }
    public void setCitationAccuracy(Double citationAccuracy) { this.citationAccuracy = citationAccuracy; }
    public Double getPointCoverage() { return pointCoverage; }
    public void setPointCoverage(Double pointCoverage) { this.pointCoverage = pointCoverage; }
    public Double getRefusalRate() { return refusalRate; }
    public void setRefusalRate(Double refusalRate) { this.refusalRate = refusalRate; }
    public Double getAvgTokens() { return avgTokens; }
    public void setAvgTokens(Double avgTokens) { this.avgTokens = avgTokens; }
    public Double getAvgDurationMs() { return avgDurationMs; }
    public void setAvgDurationMs(Double avgDurationMs) { this.avgDurationMs = avgDurationMs; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
