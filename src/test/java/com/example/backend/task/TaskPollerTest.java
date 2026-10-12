package com.example.backend.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TaskPoller} 单元测试：到期任务「DB CAS 认领 → 每日上限 → 投递」主链路与防重。
 */
@ExtendWith(MockitoExtension.class)
class TaskPollerTest {

    @Mock private ScheduledTaskRepository taskRepository;
    @Mock private TaskService taskService;
    @Mock private TaskRunner runner;

    private TaskPoller poller;

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void setUp() {
        poller = new TaskPoller(new TaskProperties(), taskRepository, taskService, runner);
    }

    private static ScheduledTaskEntity dueTask() {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(3L);
        task.setUserId("alice");
        task.setName("早报");
        task.setPrompt("x");
        task.setCronExpr("0 0 8 * * *");
        task.setEnabled(true);
        task.setNextRunAt(LocalDateTime.of(2026, 10, 12, 7, 0));
        return task;
    }

    @Test
    void dispatch_claimsViaCasThenSubmits() {
        ScheduledTaskEntity task = dueTask();
        when(taskService.zone()).thenReturn(SH);
        when(taskRepository.claimNextRun(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskService.isDailyCapReached(task)).thenReturn(false);
        when(runner.submit(task)).thenReturn(TaskRunner.SubmitResult.SUBMITTED);

        poller.dispatch(task, LocalDateTime.of(2026, 10, 12, 7, 59));

        // CAS：old=读取值，new=从 now 重算的下一次（当天 08:00）
        verify(taskRepository).claimNextRun(3L,
                LocalDateTime.of(2026, 10, 12, 7, 0),
                LocalDateTime.of(2026, 10, 12, 8, 0));
        verify(runner).submit(task);
    }

    @Test
    void dispatch_casLost_doesNothing() {
        ScheduledTaskEntity task = dueTask();
        when(taskService.zone()).thenReturn(SH);
        when(taskRepository.claimNextRun(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);

        poller.dispatch(task, LocalDateTime.of(2026, 10, 12, 7, 59));

        // 已被其他实例认领：不投递、不再查上限
        verify(taskService, never()).isDailyCapReached(any());
        verifyNoInteractions(runner);
    }

    @Test
    void dispatch_dailyCapReached_skipsSubmit() {
        ScheduledTaskEntity task = dueTask();
        when(taskService.zone()).thenReturn(SH);
        when(taskRepository.claimNextRun(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskService.isDailyCapReached(task)).thenReturn(true);

        poller.dispatch(task, LocalDateTime.of(2026, 10, 12, 7, 59));

        verify(runner, never()).submit(any());
    }

    @Test
    void dispatch_busyOrRejected_recordsFailedRow() {
        ScheduledTaskEntity task = dueTask();
        when(taskService.zone()).thenReturn(SH);
        when(taskRepository.claimNextRun(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskService.isDailyCapReached(task)).thenReturn(false);
        when(runner.submit(task)).thenReturn(TaskRunner.SubmitResult.BUSY);

        poller.dispatch(task, LocalDateTime.of(2026, 10, 12, 7, 59));

        verify(runner).recordDispatchFailure(task, "上一次执行尚未结束，本次跳过");
    }

    @Test
    void poll_disabledFeature_skipsScan() {
        TaskProperties props = new TaskProperties();
        props.setEnabled(false);
        // 用关闭开关的配置重建 poller
        poller = new TaskPoller(props, taskRepository, taskService, runner);

        poller.poll();

        verifyNoInteractions(taskRepository, taskService, runner);
    }

    @Test
    void poll_scansDueAndDispatchesEach() {
        TaskProperties props = new TaskProperties();
        poller = new TaskPoller(props, taskRepository, taskService, runner);
        ScheduledTaskEntity task = dueTask();
        when(taskService.zone()).thenReturn(SH);
        when(taskRepository.findTop20ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
                any(LocalDateTime.class))).thenReturn(java.util.List.of(task));
        when(taskRepository.claimNextRun(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskService.isDailyCapReached(task)).thenReturn(false);
        when(runner.submit(task)).thenReturn(TaskRunner.SubmitResult.SUBMITTED);

        poller.poll();

        verify(runner).submit(task);
    }
}