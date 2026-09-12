package com.example.backend.skill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<SkillEntity, Long> {

    Optional<SkillEntity> findByName(String name);

    List<SkillEntity> findByBuiltinFalse();

    /** MCP 动态内置技能行（mcp:{server}），启动同步时用于清理已下线 server */
    List<SkillEntity> findByBuiltinTrueAndNameStartingWith(String prefix);
}
