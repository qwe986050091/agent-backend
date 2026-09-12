package com.example.backend.skill;

import com.example.backend.agent.AgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 业务服务
 *
 * 职责：
 *   1. Skill 元数据、用户挂载关系的 MySQL 持久化（<b>本服务是唯一数据权威</b>）
 *   2. 对话时由 ChatController 读取挂载关系与技能定义，随 payload 下发给无状态的 agent
 *
 * 无状态化说明：技能上传/挂载/卸载/删除<b>不再</b>同步调用 agent —— agent 不保存技能状态，
 * 每次对话请求实时携带生效的技能定义，因此 agent 重启/多实例都无需补偿同步。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 内置技能元数据（与 agent 工程内置 Skill 对应，首次启动写入 skill 表） */
    public static final List<SkillEntity> BUILTIN_SKILLS = List.of(
            builtin("calculator", "数学计算技能：加减乘除、幂运算等"),
            builtin("datetime", "时间日期技能：获取当前日期、时间、星期等"),
            builtin("web", "联网助手：联网搜索最新信息、抓取网页正文"),
            builtin("http-api", "HTTP 请求：调用第三方 HTTP API（高危，每次需确认）"));

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
     * 上传/更新 Skill：直接落库（MySQL 为权威，对话时随 payload 下发给 agent）
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

        log.info("[SkillService] 用户 {} 上传/更新 Skill: {}", uploader, name);
        return entity;
    }

    /** 挂载：落库即可（下次对话请求自动携带） */
    @Transactional
    public boolean mount(String userId, String skillName) {
        if (skillRepository.findByName(skillName).isEmpty()) {
            return false;
        }
        if (!userSkillRepository.existsByUserIdAndSkillName(userId, skillName)) {
            UserSkillEntity mount = new UserSkillEntity();
            mount.setUserId(userId);
            mount.setSkillName(skillName);
            userSkillRepository.save(mount);
        }
        return true;
    }

    /** 卸载：删除挂载关系 */
    @Transactional
    public boolean unmount(String userId, String skillName) {
        boolean existed = userSkillRepository.existsByUserIdAndSkillName(userId, skillName);
        if (existed) {
            userSkillRepository.deleteByUserIdAndSkillName(userId, skillName);
        }
        return existed;
    }

    /** 删除自定义 Skill：清理挂载关系 + 删元数据 */
    @Transactional
    public boolean delete(String skillName) {
        SkillEntity entity = skillRepository.findByName(skillName).orElse(null);
        if (entity == null || entity.isBuiltin()) {
            return false;
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

    /**
     * 与 agent 内置技能目录同步（含 mcp:{server} 动态技能）：
     * - catalog 中存在、库里缺失 → 插入 builtin 行；
     * - mcp:* 描述变更 → 更新；
     * - catalog 中已不存在的 mcp:* 行 → 连同用户挂载关系删除；
     * agent 不可达（catalog=null）时跳过，不阻塞启动。
     */
    @Transactional
    public void syncBuiltinCatalog() {
        List<Map<String, Object>> catalog = agentClient.listBuiltinSkills();
        if (catalog == null) {
            log.warn("[SkillService] agent 内置技能目录不可达，跳过 MCP 目录同步");
            return;
        }
        Set<String> liveNames = new java.util.HashSet<>();
        for (Map<String, Object> item : catalog) {
            Object nameVal = item.get("name");
            if (nameVal == null) {
                continue;
            }
            String name = nameVal.toString();
            String desc = item.get("shortDescription") == null
                    ? "" : item.get("shortDescription").toString();
            liveNames.add(name);

            SkillEntity entity = skillRepository.findByName(name).orElse(null);
            if (entity == null) {
                skillRepository.save(builtin(name, desc));
                log.info("[SkillService] 内置 Skill 元数据落库（agent 目录同步）: {}", name);
            } else if (entity.isBuiltin()
                    && name.startsWith("mcp:")
                    && !desc.equals(entity.getShortDescription())) {
                entity.setShortDescription(desc);
                skillRepository.save(entity);
            } else if (!entity.isBuiltin()) {
                log.warn("[SkillService] agent 内置技能名与自定义技能冲突，保留自定义行: {}", name);
            }
        }

        for (SkillEntity stale : skillRepository.findByBuiltinTrueAndNameStartingWith("mcp:")) {
            if (!liveNames.contains(stale.getName())) {
                userSkillRepository.deleteBySkillName(stale.getName());
                skillRepository.delete(stale);
                log.info("[SkillService] 移除已下线 MCP 技能及其挂载关系: {}", stale.getName());
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
