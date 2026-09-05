package com.example.backend.agent;

import com.example.backend.skill.SkillEntity;
import com.example.backend.skill.SkillRepository;
import com.example.backend.skill.SkillService;
import com.example.backend.skill.UserSkillEntity;
import com.example.backend.skill.UserSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 启动同步：backend 就绪后把 MySQL 中的 Skill 状态补偿同步给 agent 工程。
 *
 * 背景：agent 的 SkillManager 是内存态，agent 重启后自定义 Skill 与挂载关系会丢失。
 * 同步内容：
 *   1. 内置 Skill 元数据落库（skill 表）
 *   2. DB 中自定义 Skill 若 agent 未注册 → 重新 upload
 *   3. DB 中全部用户挂载关系 → 重新 mount（幂等）
 *
 * agent 不可达时仅告警，不阻塞 backend 启动（登录/历史等功能仍可用）。
 */
@Component
public class SkillSyncService {

    private static final Logger log = LoggerFactory.getLogger(SkillSyncService.class);

    private final AgentClient agentClient;
    private final SkillService skillService;
    private final SkillRepository skillRepository;
    private final UserSkillRepository userSkillRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillSyncService(AgentClient agentClient,
                            SkillService skillService,
                            SkillRepository skillRepository,
                            UserSkillRepository userSkillRepository) {
        this.agentClient = agentClient;
        this.skillService = skillService;
        this.skillRepository = skillRepository;
        this.userSkillRepository = userSkillRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        // 1. 内置技能元数据确保落库
        skillService.ensureBuiltinSkills();

        if (!agentClient.isHealthy()) {
            log.warn("[SkillSync] agent 服务不可达（{}），跳过同步。请先启动 agent 工程，对话/技能功能依赖它。",
                    "http://localhost:8080");
            return;
        }

        try {
            Set<String> agentSkillNames = agentClient.listSkillNames();

            // 2. 自定义 Skill 补偿注册
            int uploaded = 0;
            for (SkillEntity entity : skillRepository.findAll()) {
                if (entity.isBuiltin()) {
                    continue;
                }
                if (!agentSkillNames.contains(entity.getName())) {
                    String json = objectMapper.writeValueAsString(java.util.Map.of(
                            "name", entity.getName(),
                            "shortDescription", nullToEmpty(entity.getShortDescription()),
                            "detailedDescription", nullToEmpty(entity.getDetailedDescription()),
                            "systemPrompt", nullToEmpty(entity.getSystemPrompt()),
                            "toolNames", parseToolNames(entity.getToolNamesJson())
                    ));
                    agentClient.uploadSkill(json);
                    uploaded++;
                }
            }

            // 3. 挂载关系补偿
            int mounted = 0;
            for (UserSkillEntity mount : userSkillRepository.findAll()) {
                try {
                    agentClient.mount(mount.getUserId(), mount.getSkillName());
                    mounted++;
                } catch (Exception e) {
                    log.warn("[SkillSync] 挂载补偿失败 {} -> {}: {}",
                            mount.getUserId(), mount.getSkillName(), e.getMessage());
                }
            }

            log.info("[SkillSync] 同步完成：补偿注册自定义 Skill {} 个，恢复挂载关系 {} 条", uploaded, mounted);
        } catch (Exception e) {
            log.warn("[SkillSync] 同步过程出错: {}", e.getMessage());
        }
    }

    private List<String> parseToolNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
