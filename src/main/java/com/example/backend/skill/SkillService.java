package com.example.backend.skill;

import com.example.backend.agent.AgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Skill 业务服务
 *
 * 职责：
 *   1. Skill 元数据、用户挂载关系的 MySQL 持久化（本服务是数据权威）
 *   2. 所有变更通过 HTTP 同步给 agent 工程（agent 侧内存态供对话时使用）
 *
 * 约定操作顺序：先调 agent（外部内存态，失败直接报错），成功后落库。
 * agent 重启导致内存丢失的情况，由 SkillSyncService 在 backend 启动/就绪时补偿同步。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 内置技能元数据（与 agent 工程内置 Skill 对应，首次启动写入 skill 表） */
    public static final List<SkillEntity> BUILTIN_SKILLS = List.of(
            builtin("calculator", "数学计算技能：加减乘除、幂运算等"),
            builtin("datetime", "时间日期技能：获取当前日期、时间、星期等"));

    private final SkillRepository skillRepository;
    private final UserSkillRepository userSkillRepository;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;

    public SkillService(SkillRepository skillRepository,
                        UserSkillRepository userSkillRepository,
                        AgentClient agentClient) {
        this.skillRepository = skillRepository;
        this.userSkillRepository = userSkillRepository;
        this.agentClient = agentClient;
        this.objectMapper = new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /** 平台全部 Skill */
    public List<SkillEntity> listAll() {
        return skillRepository.findAll();
    }

    /** 用户已挂载的 Skill 名称 */
    public Set<String> mountedNames(String userId) {
        return userSkillRepository.findByUserId(userId).stream()
                .map(UserSkillEntity::getSkillName)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * 上传/更新 Skill：转发给 agent 注册，成功后落库
     */
    @Transactional
    public SkillEntity upload(SkillUploadRequest req, String uploader) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("Skill 名称不能为空");
        }
        String name = req.name().trim();

        SkillEntity existing = skillRepository.findByName(name).orElse(null);
        if (existing != null && existing.isBuiltin()) {
            throw new IllegalArgumentException("内置 Skill 名称不可覆盖: " + name);
        }

        // 1. 转发给 agent（字段与 agent 的 SkillDefinition 完全一致，直接序列化）
        try {
            String json = objectMapper.writeValueAsString(req);
            agentClient.uploadSkill(json);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 服务注册 Skill 失败: " + rootMessage(e), e);
        }

        // 2. 落库
        SkillEntity entity = existing != null ? existing : new SkillEntity();
        entity.setName(name);
        entity.setShortDescription(req.shortDescription());
        entity.setDetailedDescription(req.detailedDescription());
        entity.setSystemPrompt(req.systemPrompt());
        try {
            entity.setToolNamesJson(objectMapper.writeValueAsString(
                    req.toolNames() == null ? List.of() : req.toolNames()));
        } catch (Exception e) {
            entity.setToolNamesJson("[]");
        }
        entity.setBuiltin(false);
        entity.setCreatedBy(uploader);
        skillRepository.save(entity);

        log.info("[SkillService] 用户 {} 上传 Skill: {}", uploader, name);
        return entity;
    }

    /** 挂载：先同步 agent，再落库 */
    @Transactional
    public boolean mount(String userId, String skillName) {
        if (skillRepository.findByName(skillName).isEmpty()) {
            return false;
        }
        try {
            agentClient.mount(userId, skillName);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 服务挂载失败: " + rootMessage(e), e);
        }
        if (!userSkillRepository.existsByUserIdAndSkillName(userId, skillName)) {
            UserSkillEntity mount = new UserSkillEntity();
            mount.setUserId(userId);
            mount.setSkillName(skillName);
            userSkillRepository.save(mount);
        }
        return true;
    }

    /** 卸载 */
    @Transactional
    public boolean unmount(String userId, String skillName) {
        boolean existed = userSkillRepository.existsByUserIdAndSkillName(userId, skillName);
        try {
            agentClient.unmount(userId, skillName);
        } catch (Exception e) {
            log.warn("[SkillService] agent 卸载失败（继续清理本地关系）: {}", rootMessage(e));
        }
        if (existed) {
            userSkillRepository.deleteByUserIdAndSkillName(userId, skillName);
        }
        return existed;
    }

    /** 删除自定义 Skill：注销 agent + 清理挂载关系 + 删元数据 */
    @Transactional
    public boolean delete(String skillName) {
        SkillEntity entity = skillRepository.findByName(skillName).orElse(null);
        if (entity == null || entity.isBuiltin()) {
            return false;
        }
        try {
            agentClient.unregister(skillName);
        } catch (Exception e) {
            log.warn("[SkillService] agent 注销失败（继续清理本地数据）: {}", rootMessage(e));
        }
        userSkillRepository.deleteBySkillName(skillName);
        skillRepository.delete(entity);
        log.info("[SkillService] 删除自定义 Skill: {}", skillName);
        return true;
    }

    /** agent 侧可用的内置 Tool 名称（上传 Skill 时勾选） */
    public List<String> availableTools() {
        try {
            return agentClient.listToolNames().stream().sorted().toList();
        } catch (Exception e) {
            log.warn("[SkillService] 获取 agent Tool 列表失败: {}", rootMessage(e));
            return List.of();
        }
    }

    /** 内置技能元数据首次落库 */
    @Transactional
    public void ensureBuiltinSkills() {
        for (SkillEntity builtin : BUILTIN_SKILLS) {
            if (skillRepository.findByName(builtin.getName()).isEmpty()) {
                skillRepository.save(builtin);
                log.info("[SkillService] 内置 Skill 元数据落库: {}", builtin.getName());
            }
        }
    }

    private static SkillEntity builtin(String name, String shortDescription) {
        SkillEntity e = new SkillEntity();
        e.setName(name);
        e.setShortDescription(shortDescription);
        e.setBuiltin(true);
        e.setCreatedBy("system");
        e.setToolNamesJson("[]");
        return e;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
