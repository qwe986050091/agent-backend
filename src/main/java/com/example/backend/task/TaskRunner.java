package com.example.backend.task;

import com.example.backend.chat.AgentTurnService;
import com.example.backend.chat.ConversationEntity;
import com.example.backend.chat.ConversationRepository;
import com.example.backend.notification.NotificationService;
import com.example.backend.ratelimit.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时任务执行器（P2-9 M1b）：把一次投递变成「专属会话里的一整个对话回合」。
 *
 * 执行流程（对应设计稿 §二）：
 * <ol>
 *   <li>僵尸清理：同任务 running 超时未收尾的旧行标 failed</li>
 *   <li>日预算闸：{@link RateLimitService#isDailyBudgetExceeded} 超限只记 skipped_budget（不发 agent 调用）</li>
 *   <li>专属会话懒创建（被用户删除后自动重建）</li>
 *   <li>执行记录落 running 行（traceId 直通现有审计）</li>
 *   <li>消息组装（前缀注入当前时间）→ {@link AgentTurnService#executeTurn}（同步，含熔断/重试）</li>
 *   <li>收尾 run 行（success/failed + 耗时 + 助手消息 id/可读错误）+ last_run_at + next_run_at 重算</li>
 *   <li>[M2] 成功后写站内通知（best-effort，独立于执行结果）</li>
 * </ol>
 *
 * <b>审批交互：</b>同步通道无审批出口，未授权高危工具自动拒绝；
 * 任务级预授权（tools_whitelist）与会话级授权并入 allowedTools 后自动放行。
 * <b>配额口径：</b>不占用户 RPM/在途交互名额，但 token 正常计费并计入日预算。
 */
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    /** 任务锁键前缀（Redis，跨「认领后进程崩溃」的兜底；TTL 见 app.task.lock-ttl-seconds） */
    private static final String LOCK_PREFIX = "task:lock:";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** 投递结果：成功入池 / 上一次执行未结束 / 执行池满 */
    public enum SubmitResult { SUBMITTED, BUSY, REJECTED }

    private final TaskProperties props;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final ConversationRepository conversationRepository;
    private final AgentTurnService turnService;
    private final RateLimitService rateLimitService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 执行池：有界（core 1 / max=app.task.max-concurrent-runs / 队列 20），
     * AbortPolicy 拒绝——慢任务不阻塞 poller，池满快速失败而非无限堆积。
     */
    private final ThreadPoolExecutor runExecutor;

    public TaskRunner(TaskProperties props,
                      ScheduledTaskRepository taskRepository,
                      ScheduledTaskRunRepository runRepository,
                      ConversationRepository conversationRepository,
                      AgentTurnService turnService,
                      RateLimitService rateLimitService,
                      NotificationService notificationService,
                      StringRedisTemplate redis,
                      ObjectMapper objectMapper) {
        this.props = props;
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.conversationRepository = conversationRepository;
        this.turnService = turnService;
        this.rateLimitService = rateLimitService;
        this.notificationService = notificationService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        int max = Math.max(1, props.getMaxConcurrentRuns());
        this.runExecutor = new ThreadPoolExecutor(1, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                r -> {
                    Thread t = new Thread(r, "task-runner-" + THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdownExecutor() {
        runExecutor.shutdown();
    }

    // ==================== 投递 ====================

    /**
     * 投递一次执行（poller 与 run-now 共用）：
     * 先取 Redis 任务锁（防同任务并发执行），再入有界执行池；锁由执行线程收尾时释放。
     */
    public SubmitResult submit(ScheduledTaskEntity task) {
        String lockKey = LOCK_PREFIX + task.getId();
        if (!acquireLock(lockKey)) {
            return SubmitResult.BUSY;
        }
        try {
            runExecutor.execute(() -> run(task, lockKey));
            return SubmitResult.SUBMITTED;
        } catch (RejectedExecutionException e) {
            releaseLock(lockKey);
            return SubmitResult.REJECTED;
        }
    }

    /** 投递失败（池满/上一次未结束）时记一行 failed（设计稿：拒绝时记 failed，可见可查） */
    public void recordDispatchFailure(ScheduledTaskEntity task, String message) {
        try {
            saveFinishedRun(task, LocalDateTime.now(zone()), null,
                    ScheduledTaskRunEntity.STATUS_FAILED, message, System.nanoTime());
        } catch (Exception e) {
            log.warn("[TaskRunner] 任务 {} 投递失败记录落库异常: {}", task.getId(), e.getMessage());
        }
    }

    private boolean acquireLock(String key) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1",
                    Duration.ofSeconds(props.getLockTtlSeconds()));
            return !Boolean.FALSE.equals(ok);
        } catch (Exception e) {
            // Redis 故障 fail-open：DB CAS 已是防重主保险，锁只是兜底
            log.warn("[TaskRunner] Redis 任务锁获取失败（fail-open，依赖 DB CAS 防重）: {}", e.getMessage());
            return true;
        }
    }

    private void releaseLock(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[TaskRunner] Redis 任务锁释放失败（依赖 TTL 兜底）: {}", e.getMessage());
        }
    }

    // ==================== 执行 ====================

    private void run(ScheduledTaskEntity task, String lockKey) {
        try {
            doRun(task);
        } catch (Exception e) {
            log.warn("[TaskRunner] 任务 {} 执行异常: {}", task.getId(), e.getMessage(), e);
        } finally {
            releaseLock(lockKey);
        }
    }

    void doRun(ScheduledTaskEntity task) {
        LocalDateTime startedAt = LocalDateTime.now(zone());
        long startNanos = System.nanoTime();
        cleanupZombies(task.getId(), startedAt);
        try {
            // 1. 日预算闸（超限只记执行记录，不发 agent 调用）
            if (rateLimitService.isDailyBudgetExceeded(task.getUserId())) {
                saveFinishedRun(task, startedAt, null,
                        ScheduledTaskRunEntity.STATUS_SKIPPED_BUDGET,
                        "今日 token 预算已用尽，本次跳过", startNanos);
                log.info("[TaskRunner] 任务 {}（{}）因日预算超限跳过", task.getId(), task.getName());
                return;
            }

            // 2. 专属会话（懒创建；被删除则重建）
            Long conversationId = ensureConversation(task);

            // 3. 执行记录（running；traceId 直通现有审计链路）
            String traceId = UUID.randomUUID().toString();
            ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
            run.setTaskId(task.getId());
            run.setUserId(task.getUserId());
            run.setStatus(ScheduledTaskRunEntity.STATUS_RUNNING);
            run.setTraceId(traceId);
            run.setStartedAt(startedAt);
            run = runRepository.save(run);

            // 4. 执行回合（同步；异常分类落 error_message）
            String answer = null;
            try {
                AgentTurnService.TurnResult result = turnService.executeTurn(
                        task.getUserId(), conversationId, buildPrompt(task, startedAt),
                        TaskTools.parse(task.getToolsWhitelist(), objectMapper), traceId);
                answer = result.agentResult().answer();
                finishRun(run, ScheduledTaskRunEntity.STATUS_SUCCESS, startNanos,
                        result.assistantMessageId(), null);
                log.info("[TaskRunner] 任务 {}（{}）执行成功（{}ms，消息 {}）",
                        task.getId(), task.getName(), elapsedMs(startNanos), result.assistantMessageId());
            } catch (Exception e) {
                finishRun(run, ScheduledTaskRunEntity.STATUS_FAILED, startNanos, null, describeFailure(e));
                log.warn("[TaskRunner] 任务 {}（{}）执行失败: {}", task.getId(), task.getName(), e.getMessage());
            }

            // 5. [M2] 成功后写站内通知（在结果落库之外：通知 best-effort，绝不改写执行状态）
            if (ScheduledTaskRunEntity.STATUS_SUCCESS.equals(run.getStatus())) {
                notificationService.notifyTaskResult(
                        task.getUserId(), task.getName(), answer, conversationId);
            }
        } finally {
            // 6. 收尾：last_run_at + next_run_at 用执行结束时刻重算（执行期间错过的点不补跑）
            refreshTaskAfterRun(task.getId(), startedAt);
        }
    }

    /** 同任务 running 且开始时间超时（进程被杀/崩溃残留）的旧行标 failed */
    private void cleanupZombies(Long taskId, LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusMinutes(props.getZombieTimeoutMinutes());
            List<ScheduledTaskRunEntity> zombies = runRepository
                    .findByTaskIdAndStatusAndStartedAtLessThan(
                            taskId, ScheduledTaskRunEntity.STATUS_RUNNING, cutoff);
            for (ScheduledTaskRunEntity zombie : zombies) {
                zombie.setStatus(ScheduledTaskRunEntity.STATUS_FAILED);
                zombie.setErrorMessage("zombie：执行超时未收尾（进程重启/崩溃）");
                zombie.setFinishedAt(now);
                runRepository.save(zombie);
                log.warn("[TaskRunner] 任务 {} 清理僵尸执行行 {}", taskId, zombie.getId());
            }
        } catch (Exception e) {
            log.warn("[TaskRunner] 僵尸行清理失败（不影响执行）: {}", e.getMessage());
        }
    }

    /** 任务专属会话（懒创建）；用户已删除/归属异常时重建并回写绑定 */
    private Long ensureConversation(ScheduledTaskEntity task) {
        Long conversationId = task.getConversationId();
        if (conversationId != null) {
            ConversationEntity conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv != null && task.getUserId().equals(conv.getUserId())) {
                return conversationId;
            }
            log.info("[TaskRunner] 任务 {} 专属会话 {} 已失效，自动重建", task.getId(), conversationId);
        }
        ConversationEntity conv = new ConversationEntity();
        conv.setUserId(task.getUserId());
        conv.setTitle("⏰ " + task.getName());
        conversationRepository.save(conv);
        // 定向 UPDATE 绑定（不能让整体 save 把内存中已过期的 next_run_at 写回）
        taskRepository.bindConversation(task.getId(), conv.getId());
        log.info("[TaskRunner] 任务 {} 创建专属会话 {}", task.getId(), conv.getId());
        return conv.getId();
    }

    /** 消息组装：前缀注入当前时间（LLM 拿到时间上下文，替代模板引擎） */
    private static String buildPrompt(ScheduledTaskEntity task, LocalDateTime at) {
        String weekday = at.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return "【定时任务·" + task.getName() + "】\n当前时间：" + at.format(TIME_FMT)
                + "（" + weekday + "）\n\n" + task.getPrompt();
    }

    private void finishRun(ScheduledTaskRunEntity run, String status, long startNanos,
                           Long assistantMessageId, String error) {
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now(zone()));
        run.setDurationMs(elapsedMs(startNanos));
        run.setAssistantMessageId(assistantMessageId);
        run.setErrorMessage(truncate(error, 500));
        runRepository.save(run);
    }

    private void saveFinishedRun(ScheduledTaskEntity task, LocalDateTime startedAt, String traceId,
                                 String status, String error, long startNanos) {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setTaskId(task.getId());
        run.setUserId(task.getUserId());
        run.setStatus(status);
        run.setTraceId(traceId);
        run.setStartedAt(startedAt);
        run.setFinishedAt(LocalDateTime.now(zone()));
        run.setDurationMs(elapsedMs(startNanos));
        run.setErrorMessage(truncate(error, 500));
        runRepository.save(run);
    }

    /**
     * 执行收尾刷新：last_run_at=本次开始时刻；启用中则 next_run_at 用结束时刻重算
     * （停机/执行期间错过的执行点直接跳过，v1 明确不补跑）。
     * 重新读库再保存：任务可能在执行期间被用户修改/删除。
     */
    private void refreshTaskAfterRun(Long taskId, LocalDateTime startedAt) {
        try {
            taskRepository.findById(taskId).ifPresent(current -> {
                current.setLastRunAt(startedAt);
                if (current.isEnabled()) {
                    LocalDateTime next = TaskCron.nextAfter(
                            current.getCronExpr(), LocalDateTime.now(zone()), zone());
                    current.setNextRunAt(next);
                }
                taskRepository.save(current);
            });
        } catch (Exception e) {
            log.warn("[TaskRunner] 任务 {} 收尾刷新失败: {}", taskId, e.getMessage());
        }
    }

    private ZoneId zone() {
        return ZoneId.of(props.getZone());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String describeFailure(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return truncate(message, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}