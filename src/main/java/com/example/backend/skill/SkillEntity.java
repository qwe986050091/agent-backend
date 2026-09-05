package com.example.backend.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Skill 元数据（平台所有已注册技能，含内置与用户上传）
 */
@Entity
@Table(name = "skill", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class SkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 技能唯一标识 */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String detailedDescription;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    /** 引用的内置 Tool 名称列表，JSON 数组字符串，如 ["calculatorTools","dateTimeTools"] */
    @Column(length = 500)
    private String toolNamesJson;

    /** 是否内置技能（内置不可删除/覆盖） */
    @Column(nullable = false)
    private boolean builtin = false;

    /** 上传者用户名（内置为 system） */
    @Column(length = 100)
    private String createdBy;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }
    public String getDetailedDescription() { return detailedDescription; }
    public void setDetailedDescription(String detailedDescription) { this.detailedDescription = detailedDescription; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getToolNamesJson() { return toolNamesJson; }
    public void setToolNamesJson(String toolNamesJson) { this.toolNamesJson = toolNamesJson; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
