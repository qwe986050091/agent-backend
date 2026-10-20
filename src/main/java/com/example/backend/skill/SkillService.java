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
            builtin("http-api", "HTTP 请求：调用第三方 HTTP API（高危，每次需确认）"),
            // ===== P1-C：三类内置子代理（type=agent，拥有独立推理循环与私有工具） =====
            builtinAgent("researcher", "检索专家：联网检索与网页正文抓取，按知识库/公开资料给出带来源的事实",
                    """
                    你是检索专家，负责为一个子任务收集可靠资料。
                    工作方式：
                    1. 先明确需要查证的关键事实，再用 webSearch 搜索、用 fetchWebPage 抓取正文；
                    2. 优先采用权威来源，交叉核对至少两个来源，冲突时如实说明；
                    3. 只写有来源支撑的结论，每条结论标注来源（URL 或文档名）；
                    4. 找不到就明确说「未检索到」，不得编造。
                    输出：分点列出事实与来源，末尾给出仍不确定的点。
                    """,
                    "[\"webSearchTools\",\"webFetchTools\"]"),
            builtinAgent("executor", "执行专家：按指令调用计算/时间/HTTP 工具完成确定性操作",
                    """
                    你是执行专家，负责把子任务里的确定性操作真正做完。
                    工作方式：
                    1. 先复述要执行的操作与参数，确认没有歧义；
                    2. 需要计算用 calculator，需要当前时间用 datetime，需要调外部接口用 HTTP 工具；
                    3. 严格按工具返回结果作答，不得推测或改写返回值；
                    4. 工具失败时如实报告错误与可能原因，不要假装成功。
                    输出：执行结果 + 关键参数 + 失败时的错误摘要。
                    """,
                    "[\"calculatorTools\",\"dateTimeTools\",\"httpRequestTools\"]"),
            builtinAgent("verifier", "校验专家：交叉核对前序结论与来源，判定通过/不通过并给出修正意见",
                    """
                    你是校验专家，负责在结论交付前做一次独立复核。
                    工作方式：
                    1. 对照目标检查：是否回答了问题、关键结论是否有来源支撑、是否存在自相矛盾或臆造；
                    2. 只依据给定的材料判断，不引入新的外部事实，也不替执行者补全缺失信息；
                    3. 回答的第一行必须是「通过」或「不通过」，不得有前缀；
                    4. 判定「不通过」时，逐条列出问题与可执行的修正建议（指明是哪一步、改成什么）。
                    """,
                    "[]"));

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
        entity.setType(normalizeSkillType(req.type()));
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
        e.setType(SkillEntity.TYPE_TOOL);
        return e;
    }

    /** 内置子代理（type=agent）：自带系统提示与私有工具，主 Agent 以 delegate_{name} 委派 */
    private static SkillEntity builtinAgent(String name, String shortDescription,
                                            String systemPrompt, String toolNamesJson) {
        SkillEntity e = builtin(name, shortDescription);
        e.setType(SkillEntity.TYPE_AGENT);
        e.setSystemPrompt(systemPrompt);
        e.setToolNamesJson(toolNamesJson);
        return e;
    }

    /**
     * 归一化技能类型：null/空白 → tool；仅接受 tool/agent，其余抛异常（数据不一致要暴露，不静默降级）。
     */
    static String normalizeSkillType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SkillEntity.TYPE_TOOL;
        }
        String type = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SkillEntity.TYPE_TOOL.equals(type) && !SkillEntity.TYPE_AGENT.equals(type)) {
            throw new IllegalArgumentException("Skill type 仅支持 tool/agent，收到: " + raw);
        }
        return type;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
