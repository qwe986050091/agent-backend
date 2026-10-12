package com.example.backend.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务 poller（P2-9）：按固定周期扫到期任务，只做「认领 + 投递」，不执行。
 *
 * 认领双保险：
 * ① 主：DB CAS（{@link ScheduledTaskRepository#claimNextRun}）——更新行数 1 者获得执行权（多实例安全）；
 * ② 辅：Redis SETNX（在 {@link TaskRunner#submit} 内）——跨「认领后进程崩溃」兜底。
 *
 * 停机/执行期间错过的执行点不补跑（next_run_at 由认领/收尾重算推进）。
 */
@Component
public class TaskPoller {

    private static final Logger log = LoggerFactory.getLogger(TaskPoller.class);

    private final TaskProperties props;
    private final ScheduledTaskRepository taskRepository;
    private final TaskService taskService;
    private final TaskRunner runner;

    public TaskPoller(TaskProperties props,
                      ScheduledTaskRepository taskRepository,
                      TaskService taskService,
                      TaskRunner runner) {
        this.props = props;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "#{${app.task.poll-interval-seconds} * 1000}",
            initialDelayString = "#{${app.task.poll-interval-seconds} * 1000 / 2}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(taskService.zone());
        List<ScheduledTaskEntity> due = taskRepository
                .findTop20ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
        if (due.isEmpty()) {
            return;
        }
        for (ScheduledTaskEntity task : due) {
            try {
                dispatch(task, now);
            } catch (Exception e) {
                log.warn("[TaskPoller] 任务 {} 投递异常: {}", task.getId(), e.getMessage());
            }
        }
    }

    /** 认领 + 投递单个到期任务（包可见，便于单测） */
    void dispatch(ScheduledTaskEntity task, LocalDateTime now) {
        LocalDateTime oldNext = task.getNextRunAt();
        LocalDateTime newNext = TaskCron.nextAfter(task.getCronExpr(), now, taskService.zone());
        if (newNext == null) {
            log.warn("[TaskPoller] 任务 {} cron 无下次执行时间，跳过", task.getId());
            return;
        }
        // 主认领：CAS 推进 next_run_at；更新行数非 1 = 已被其他实例/收尾流程推进
        int claimed = taskRepository.claimNextRun(task.getId(), oldNext, newNext);
        if (claimed != 1) {
            return;
        }
        // 每日执行上限（含 run-now）：超额不再投递（next_run_at 已被推进，下个周期再看）
        if (taskService.isDailyCapReached(task)) {
            log.info("[TaskPoller] 任务 {} 今日执行次数已达上限 {}，跳过投递",
                    task.getId(), props.getMaxRunsPerDay());
            return;
        }
        switch (runner.submit(task)) {
            case SUBMITTED -> log.info("[TaskPoller] 任务 {}（{}）已投递执行", task.getId(), task.getName());
            case BUSY -> runner.recordDispatchFailure(task, "上一次执行尚未结束，本次跳过");
            case REJECTED -> runner.recordDispatchFailure(task, "执行池已满，本次跳过");
        }
    }
}