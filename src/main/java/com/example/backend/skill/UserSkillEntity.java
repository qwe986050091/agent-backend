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
 * 用户-Skill 挂载关系
 */
@Entity
@Table(name = "user_skill",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "skill_name"}))
public class UserSkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 挂载用户（用户名） */
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    /** 被挂载的 Skill 名称 */
    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    @Column(name = "mounted_at")
    private LocalDateTime mountedAt;

    @PrePersist
    void onCreate() {
        if (mountedAt == null) {
            mountedAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public LocalDateTime getMountedAt() { return mountedAt; }
    public void setMountedAt(LocalDateTime mountedAt) { this.mountedAt = mountedAt; }
}
