package com.example.backend.agent;

import com.example.backend.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动初始化（无状态化后已大幅精简）。
 *
 * 背景：早期 agent 的 SkillManager 为内存态，agent 重启后自定义 Skill 与挂载关系会丢失，
 * 需要 backend 在启动时把 MySQL 状态"补偿同步"给 agent。
 *
 * 现状：agent 已无状态化 —— 技能定义与挂载关系以 MySQL 为唯一权威，每次对话请求
 * 由 ChatController 实时组装下发，agent 重启/多实例均无状态丢失，<b>不再需要补偿同步</b>。
 * 本服务仅保留：启动时确保内置技能元数据落库（skill 表）。
 */
@Component
public class SkillSyncService {

    private static final Logger log = LoggerFactory.getLogger(SkillSyncService.class);

    private final SkillService skillService;

    public SkillSyncService(SkillService skillService) {
        this.skillService = skillService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initOnStartup() {
        skillService.ensureBuiltinSkills();
        // 同步 agent 内置目录（web/http-api/mcp:*）：补齐新技能、清理已下线 MCP server；
        // agent 不可达时仅 WARN 不阻塞启动（对话时自定义技能解析仍可用静态表）
        try {
            skillService.syncBuiltinCatalog();
        } catch (Exception e) {
            log.warn("[SkillSync] 内置技能目录同步失败（不影响启动）: {}", e.getMessage());
        }
        log.info("[SkillSync] 启动初始化完成：内置技能元数据已确保落库（agent 无状态，无需补偿同步）");
    }
}
