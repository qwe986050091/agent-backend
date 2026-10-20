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
 * 一次评测运行内的逐例结果（P0-B）。表结构由 schema.sql 创建（eval_case_result）。
 */
@Entity
@Table(name = "eval_case_result")
public class EvalCaseResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;

    @Column(length = 30)
    private String category;

    @Column(nullable = false)
    private Boolean passed = false;

    @Column(name = "failed_checks", length = 300)
    private String failedChecks;

    @Column(name = "checks_json", columnDefinition = "JSON")
    private String checksJson;

    @Column(name = "answer_excerpt", length = 1000)
    private String answerExcerpt;

    @Column(name = "actual_tools", length = 500)
    private String actualTools;

    @Column(name = "cited_docs", length = 500)
    private String citedDocs;

    @Column(name = "retrieved_docs", length = 500)
    private String retrievedDocs;

    @Column(nullable = false)
    private Integer tokens = 0;

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
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public String getFailedChecks() { return failedChecks; }
    public void setFailedChecks(String failedChecks) { this.failedChecks = failedChecks; }
    public String getChecksJson() { return checksJson; }
    public void setChecksJson(String checksJson) { this.checksJson = checksJson; }
    public String getAnswerExcerpt() { return answerExcerpt; }
    public void setAnswerExcerpt(String answerExcerpt) { this.answerExcerpt = answerExcerpt; }
    public String getActualTools() { return actualTools; }
    public void setActualTools(String actualTools) { this.actualTools = actualTools; }
    public String getCitedDocs() { return citedDocs; }
    public void setCitedDocs(String citedDocs) { this.citedDocs = citedDocs; }
    public String getRetrievedDocs() { return retrievedDocs; }
    public void setRetrievedDocs(String retrievedDocs) { this.retrievedDocs = retrievedDocs; }
    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
