package com.example.backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskService} 单元测试：防呆校验（cron/配额/字数）、CRUD 语义（启停重算）、
 * run-now 三道闸（功能开关/频率/每日上限/池投递结果）。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock private ScheduledTaskRepository taskRepository;
    @Mock private ScheduledTaskRunRepository runRepository;
    @Mock private TaskRunner taskRunner;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private TaskProperties props;
    private TaskService service;

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void setUp() {
        props = new TaskProperties();
        service = new TaskService(props, taskRepository, runRepository, taskRunner, redis, new ObjectMapper());
    }

    // ==================== 创建校验 ====================

    @Test
    void create_rejectsTooFrequentCron() {
        TaskRequest req = new TaskRequest("早报", "今天有什么新闻", "*/1 * * * * *", List.of(), true);
        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过于频繁");
    }

    @Test
    void create_rejectsBlankNameAndOverlongPrompt() {
        assertThatThrownBy(() -> service.create("alice",
                new TaskRequest(" ", "x", "0 0 8 * * *", List.of(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务名称不能为空");
        String longPrompt = "x".repeat(4001);
        assertThatThrownBy(() -> service.create("alice",
                new TaskRequest("早报", longPrompt, "0 0 8 * * *", List.of(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    void create_rejectsWhenQuotaExceeded() {
        when(taskRepository.countByUserIdAndEnabledTrue("alice")).thenReturn(10L);
        assertThatThrownBy(() -> service.create("alice",
                new TaskRequest("早报", "x", "0 0 8 * * *", List.of(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void create_computesNextRunAndSerializesTools() {
        when(taskRepository.countByUserIdAndEnabledTrue("alice")).thenReturn(0L);
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskEntity saved = service.create("alice",
                new TaskRequest("早报", "今天有什么新闻", "0 0 8 * * *", List.of("web_search", " web_search ", ""), null));

        assertThat(saved.getNextRunAt()).isNotNull();
        assertThat(saved.getNextRunAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(saved.isEnabled()).isTrue();
        // 工具去重/去空后入库
        assertThat(saved.getToolsWhitelist()).isNotNull().contains("web_search");
    }

    @Test
    void create_disabledTask_hasNoNextRun() {
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskEntity saved = service.create("alice",
                new TaskRequest("早报", "x", "0 0 8 * * *", List.of(), false));

        assertThat(saved.getNextRunAt()).isNull();
        // 停用任务不占配额（不查 count）
        verify(taskRepository, never()).countByUserIdAndEnabledTrue(any());
    }

    // ==================== 更新语义 ====================

    @Test
    void update_enable_recomputesNextRunAndChecksQuota() {
        ScheduledTaskEntity task = task(3L, "alice", false);
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(taskRepository.countByUserIdAndEnabledTrue("alice")).thenReturn(0L);
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskEntity updated = service.update("alice", 3L,
                new TaskRequest(null, null, null, null, true));

        assertThat(updated.isEnabled()).isTrue();
        assertThat(updated.getNextRunAt()).isNotNull();
    }

    @Test
    void update_disable_clearsNextRun() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        task.setNextRunAt(LocalDateTime.now().plusHours(1));
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskEntity updated = service.update("alice", 3L,
                new TaskRequest(null, null, null, null, false));

        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.getNextRunAt()).isNull();
    }

    @Test
    void update_cronChanged_recomputesNextRun() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        task.setCronExpr("0 0 8 * * *");
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledTaskEntity updated = service.update("alice", 3L,
                new TaskRequest(null, null, "0 0 9 * * *", null, null));

        assertThat(updated.getCronExpr()).isEqualTo("0 0 9 * * *");
        assertThat(updated.getNextRunAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    @Test
    void update_notOwned_returnsNull() {
        when(taskRepository.findByIdAndUserId(9L, "alice")).thenReturn(Optional.empty());
        assertThat(service.update("alice", 9L, new TaskRequest("x", null, null, null, null))).isNull();
    }

    // ==================== 删除 ====================

    @Test
    void delete_removesRunsThenTask() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));

        assertThat(service.delete("alice", 3L)).isTrue();

        verify(runRepository).deleteByTaskId(3L);
        verify(taskRepository).delete(task);
    }

    // ==================== run-now 三道闸 ====================

    @Test
    void runNow_disabledFeature_returns503() {
        props.setEnabled(false);
        TaskService.RunNowOutcome outcome = service.runNow("alice", 3L);
        assertThat(outcome.httpStatus()).isEqualTo(503);
        assertThat(outcome.code()).isEqualTo("TASK_DISABLED");
    }

    @Test
    void runNow_throttledWithinOneMinute() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("task:runnow:3"), eq("1"), eq(Duration.ofMinutes(1))))
                .thenReturn(false);

        TaskService.RunNowOutcome outcome = service.runNow("alice", 3L);

        assertThat(outcome.httpStatus()).isEqualTo(429);
        assertThat(outcome.code()).isEqualTo("RUN_NOW_THROTTLED");
        verify(taskRunner, never()).submit(any());
    }

    @Test
    void runNow_dailyCapReached_returns429() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(runRepository.countByTaskIdAndCreatedAtGreaterThanEqual(eq(3L), any(LocalDateTime.class)))
                .thenReturn(24L);

        TaskService.RunNowOutcome outcome = service.runNow("alice", 3L);

        assertThat(outcome.httpStatus()).isEqualTo(429);
        assertThat(outcome.code()).isEqualTo("DAILY_RUN_CAP_REACHED");
        verify(taskRunner, never()).submit(any());
    }

    @Test
    void runNow_submitOutcomes_mapToHttpStatus() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        when(taskRepository.findByIdAndUserId(3L, "alice")).thenReturn(Optional.of(task));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(runRepository.countByTaskIdAndCreatedAtGreaterThanEqual(eq(3L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(taskRunner.submit(task)).thenReturn(TaskRunner.SubmitResult.SUBMITTED,
                TaskRunner.SubmitResult.BUSY, TaskRunner.SubmitResult.REJECTED);

        assertThat(service.runNow("alice", 3L).httpStatus()).isEqualTo(200);
        assertThat(service.runNow("alice", 3L).code()).isEqualTo("TASK_BUSY");
        assertThat(service.runNow("alice", 3L).code()).isEqualTo("EXECUTOR_BUSY");
    }

    @Test
    void isDailyCapReached_countsFromBusinessDayStart() {
        ScheduledTaskEntity task = task(3L, "alice", true);
        when(runRepository.countByTaskIdAndCreatedAtGreaterThanEqual(eq(3L), any(LocalDateTime.class)))
                .thenReturn(24L);

        assertThat(service.isDailyCapReached(task)).isTrue();

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(runRepository).countByTaskIdAndCreatedAtGreaterThanEqual(eq(3L), from.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.now(SH).atStartOfDay());
    }

    @Test
    void runs_clampsPageSizeAndUsesDescOrder() {
        when(runRepository.findByTaskIdOrderByIdDesc(eq(3L), any(Pageable.class))).thenReturn(List.of());

        service.runs(3L, 0, 999);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByTaskIdOrderByIdDesc(eq(3L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    // ==================== 工具 ====================

    private static ScheduledTaskEntity task(Long id, String userId, boolean enabled) {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(id);
        task.setUserId(userId);
        task.setName("早报");
        task.setPrompt("今天有什么新闻");
        task.setCronExpr("0 0 8 * * *");
        task.setEnabled(enabled);
        return task;
    }
}