package com.example.backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 定时任务业务服务（P2-9）：CRUD + 防呆校验 + 手动执行投递 + 每日上限判定。
 *
 * 防呆（全部可配，见 {@link TaskProperties}）：
 *  - cron 合法 + 前 5 次执行最小间隔 ≥ 5 分钟；
 *  - 每用户启用任务 ≤ 10；name ≤ 100 字；prompt ≤ 4000 字；
 *  - 每任务每日执行上限（含 run-now）。
 *
 * 执行权投递统一走 {@link TaskRunner#submit}（Redis 锁 + 有界执行池）。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** run-now 频率限制键前缀（同任务 1 分钟 1 次） */
    private static final String RUN_NOW_KEY_PREFIX = "task:runnow:";

    private final TaskProperties props;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final TaskRunner taskRunner;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TaskService(TaskProperties props,
                       ScheduledTaskRepository taskRepository,
                       ScheduledTaskRunRepository runRepository,
                       TaskRunner taskRunner,
                       StringRedisTemplate redis,
                       ObjectMapper objectMapper) {
        this.props = props;
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.taskRunner = taskRunner;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 手动执行结果（controller 直接映射 HTTP 状态；code=null 表示成功投递） */
    public record RunNowOutcome(int httpStatus, String code, String message) {}

    public ZoneId zone() {
        return ZoneId.of(props.getZone());
    }

    public List<ScheduledTaskEntity> listOwned(String userId) {
        return taskRepository.findByUserIdOrderByIdDesc(userId);
    }

    public ScheduledTaskEntity findOwned(Long id, String userId) {
        return taskRepository.findByIdAndUserId(id, userId).orElse(null);
    }

    /** 执行记录分页（page 从 0 起，size ≤ 50） */
    public List<ScheduledTaskRunEntity> runs(Long taskId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 50);
        return runRepository.findByTaskIdOrderByIdDesc(taskId,
                PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "id")));
    }

    /** 列表页「上次结果」 */
    public ScheduledTaskRunEntity lastRun(Long taskId) {
        return runRepository.findFirstByTaskIdOrderByIdDesc(taskId).orElse(null);
    }

    public List<String> toolsOf(ScheduledTaskEntity task) {
        return TaskTools.parse(task.getToolsWhitelist(), objectMapper);
    }

    // ==================== CRUD ====================

    @Transactional
    public ScheduledTaskEntity create(String userId, TaskRequest req) {
        String name = requireText(req.name(), "任务名称不能为空", 100, "任务名称");
        String prompt = requireText(req.prompt(), "任务指令不能为空", 4000, "任务指令");
        String cronExpr = TaskCron.validate(req.cronExpr(), zone(), props.getMinIntervalMinutes());
        boolean enabled = req.enabled() == null || req.enabled();
        if (enabled) {
            checkQuota(userId);
        }

        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setUserId(userId);
        task.setName(name);
        task.setPrompt(prompt);
        task.setCronExpr(cronExpr);
        task.setEnabled(enabled);
        task.setToolsWhitelist(TaskTools.serialize(req.toolsWhitelist(), objectMapper));
        // 停用任务不安排下次执行；启用时 next_run_at 落库（列表展示 + 认领凭据 + 停机恢复）
        task.setNextRunAt(enabled ? TaskCron.nextAfter(cronExpr, LocalDateTime.now(zone()), zone()) : null);
        ScheduledTaskEntity saved = taskRepository.save(task);
        log.info("[Task] 用户 {} 创建任务 {}（{}，cron={}，启用={}）",
                userId, saved.getId(), name, cronExpr, enabled);
        return saved;
    }

    /** 部分更新（字段为 null 表示不改）；任务不存在/非本人返回 null（controller 映射 404） */
    @Transactional
    public ScheduledTaskEntity update(String userId, Long id, TaskRequest req) {
        ScheduledTaskEntity task = taskRepository.findByIdAndUserId(id, userId).orElse(null);
        if (task == null) {
            return null;
        }
        boolean cronChanged = false;
        if (req.name() != null) {
            task.setName(requireText(req.name(), "任务名称不能为空", 100, "任务名称"));
        }
        if (req.prompt() != null) {
            task.setPrompt(requireText(req.prompt(), "任务指令不能为空", 4000, "任务指令"));
        }
        if (req.cronExpr() != null) {
            String validated = TaskCron.validate(req.cronExpr(), zone(), props.getMinIntervalMinutes());
            cronChanged = !validated.equals(task.getCronExpr());
            task.setCronExpr(validated);
        }
        if (req.toolsWhitelist() != null) {
            task.setToolsWhitelist(TaskTools.serialize(req.toolsWhitelist(), objectMapper));
        }
        if (req.enabled() != null && req.enabled() != task.isEnabled()) {
            if (req.enabled()) {
                checkQuota(userId);
                task.setEnabled(true);
                task.setNextRunAt(TaskCron.nextAfter(task.getCronExpr(), LocalDateTime.now(zone()), zone()));
            } else {
                // 停用后不再到期（重新启用时按当时刻重算，避免恢复即补跑）
                task.setEnabled(false);
                task.setNextRunAt(null);
            }
        } else if (cronChanged && task.isEnabled()) {
            task.setNextRunAt(TaskCron.nextAfter(task.getCronExpr(), LocalDateTime.now(zone()), zone()));
        }
        return taskRepository.save(task);
    }

    /** 删除任务（连带执行记录；专属会话与消息保留——用户历史成果不销毁） */
    @Transactional
    public boolean delete(String userId, Long id) {
        ScheduledTaskEntity task = taskRepository.findByIdAndUserId(id, userId).orElse(null);
        if (task == null) {
            return false;
        }
        runRepository.deleteByTaskId(id);
        taskRepository.delete(task);
        log.info("[Task] 用户 {} 删除任务 {}（{}）；专属会话 {} 保留",
                userId, id, task.getName(), task.getConversationId());
        return true;
    }

    // ==================== 手动执行 ====================

    public RunNowOutcome runNow(String userId, Long id) {
        if (!props.isEnabled()) {
            return new RunNowOutcome(503, "TASK_DISABLED", "定时任务功能已停用，请联系管理员");
        }
        ScheduledTaskEntity task = taskRepository.findByIdAndUserId(id, userId).orElse(null);
        if (task == null) {
            return new RunNowOutcome(404, "TASK_NOT_FOUND", "任务不存在");
        }
        if (!allowRunNow(task.getId())) {
            return new RunNowOutcome(429, "RUN_NOW_THROTTLED", "手动执行过于频繁：同一任务 1 分钟内仅可执行一次");
        }
        if (isDailyCapReached(task)) {
            return new RunNowOutcome(429, "DAILY_RUN_CAP_REACHED",
                    "今日执行次数已达上限（" + props.getMaxRunsPerDay() + " 次/天），请明天再试");
        }
        return switch (taskRunner.submit(task)) {
            case SUBMITTED -> new RunNowOutcome(200, null, "已开始执行，稍后可在任务专属会话查看结果");
            case BUSY -> new RunNowOutcome(409, "TASK_BUSY", "该任务正在执行中，请稍后再试");
            case REJECTED -> new RunNowOutcome(503, "EXECUTOR_BUSY",
                    "执行池已满（最多同时 " + props.getMaxConcurrentRuns() + " 个任务），请稍后再试");
        };
    }

    /** 每任务每日执行上限（含 run-now；口径见设计稿待确认 #2） */
    public boolean isDailyCapReached(ScheduledTaskEntity task) {
        if (props.getMaxRunsPerDay() <= 0) {
            return false;
        }
        LocalDateTime dayStart = LocalDate.now(zone()).atStartOfDay();
        return runRepository.countByTaskIdAndCreatedAtGreaterThanEqual(task.getId(), dayStart)
                >= props.getMaxRunsPerDay();
    }

    // ==================== 内部 ====================

    /** run-now 频率限制（Redis，1 分钟窗口；异常 fail-open） */
    private boolean allowRunNow(Long taskId) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    RUN_NOW_KEY_PREFIX + taskId, "1", Duration.ofMinutes(1));
            return !Boolean.FALSE.equals(ok);
        } catch (Exception e) {
            log.warn("[Task] run-now 频率检查失败（fail-open）: {}", e.getMessage());
            return true;
        }
    }

    private void checkQuota(String userId) {
        if (props.getMaxPerUser() <= 0) {
            return;
        }
        if (taskRepository.countByUserIdAndEnabledTrue(userId) >= props.getMaxPerUser()) {
            throw new IllegalArgumentException(
                    "启用中的任务已达上限（" + props.getMaxPerUser() + " 个），请先停用或删除其他任务");
        }
    }

    private static String requireText(String value, String blankMessage, int maxLen, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(blankMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new IllegalArgumentException(fieldLabel + "过长（最多 " + maxLen + " 字）");
        }
        return trimmed;
    }
}