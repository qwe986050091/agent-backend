package com.example.backend.task;

import com.example.backend.agent.AgentClient;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.chat.AgentTurnService;
import com.example.backend.chat.ConversationEntity;
import com.example.backend.chat.ConversationRepository;
import com.example.backend.notification.NotificationService;
import com.example.backend.ratelimit.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TaskRunner} 单元测试：执行状态机（success/failed/skipped_budget）、
 * 预算闸、专属会话懒创建、僵尸清理、锁语义。
 */
@ExtendWith(MockitoExtension.class)
class TaskRunnerTest {

    @Mock private ScheduledTaskRepository taskRepository;
    @Mock private ScheduledTaskRunRepository runRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private AgentTurnService turnService;
    @Mock private RateLimitService rateLimitService;
    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private TaskProperties props;
    private TaskRunner runner;

    @BeforeEach
    void setUp() {
        props = new TaskProperties();
        runner = new TaskRunner(props, taskRepository, runRepository, conversationRepository,
                turnService, rateLimitService, notificationService, redis, new ObjectMapper());
    }

    private static ScheduledTaskEntity task(Long id, String userId) {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(id);
        task.setUserId(userId);
        task.setName("早报");
        task.setPrompt("今天有什么新闻");
        task.setCronExpr("0 0 8 * * *");
        task.setEnabled(true);
        return task;
    }

    private void stubRunRowId() {
        when(runRepository.save(any(ScheduledTaskRunEntity.class))).thenAnswer(inv -> {
            ScheduledTaskRunEntity run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(77L);
            }
            return run;
        });
    }

    @Test
    void budgetExceeded_recordsSkippedBudgetWithoutAgentCall() {
        when(rateLimitService.isDailyBudgetExceeded("alice")).thenReturn(true);
        ScheduledTaskEntity task = task(3L, "alice");

        runner.doRun(task);

        ArgumentCaptor<ScheduledTaskRunEntity> runs = ArgumentCaptor.forClass(ScheduledTaskRunEntity.class);
        verify(runRepository).save(runs.capture());
        assertThat(runs.getValue().getStatus()).isEqualTo(ScheduledTaskRunEntity.STATUS_SKIPPED_BUDGET);
        assertThat(runs.getValue().getErrorMessage()).contains("预算");
        assertThat(runs.getValue().getAssistantMessageId()).isNull();
        verifyNoInteractions(turnService, conversationRepository, notificationService);
    }

    @Test
    void success_recordsRunAndRefreshesTaskSchedule() {
        ScheduledTaskEntity task = task(3L, "alice");
        task.setConversationId(5L);
        ConversationEntity conv = new ConversationEntity();
        conv.setId(5L);
        conv.setUserId("alice");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conv));
        // 记录每次 save 时刻的状态（同一实体被两阶段收尾，事后断言会被后续变更覆盖）
        List<String> statusesAtSave = new java.util.ArrayList<>();
        when(runRepository.save(any(ScheduledTaskRunEntity.class))).thenAnswer(inv -> {
            ScheduledTaskRunEntity run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(77L);
            }
            statusesAtSave.add(run.getStatus());
            return run;
        });
        when(turnService.executeTurn(eq("alice"), eq(5L), anyString(), anyList(), anyString()))
                .thenReturn(new AgentTurnService.TurnResult(new AgentClient.AgentChatResult(
                        "回答", "摘要", "trace-1", TraceData.empty()), 11L, 12L, "trace-1"));
        ScheduledTaskEntity fresh = task(3L, "alice");
        when(taskRepository.findById(3L)).thenReturn(Optional.of(fresh));

        runner.doRun(task);

        // 执行记录：先 running 后 success（同一实体两阶段收尾）
        assertThat(statusesAtSave).containsExactly(
                ScheduledTaskRunEntity.STATUS_RUNNING, ScheduledTaskRunEntity.STATUS_SUCCESS);
        ArgumentCaptor<ScheduledTaskRunEntity> runs = ArgumentCaptor.forClass(ScheduledTaskRunEntity.class);
        verify(runRepository, times(2)).save(runs.capture());
        ScheduledTaskRunEntity finished = runs.getValue();
        assertThat(finished.getAssistantMessageId()).isEqualTo(12L);
        assertThat(finished.getFinishedAt()).isNotNull();

        // 消息前缀注入任务名与当前时间；run 行 traceId = 下发给回合的 traceId（直通审计）
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> traceIdArg = ArgumentCaptor.forClass(String.class);
        verify(turnService).executeTurn(eq("alice"), eq(5L), message.capture(), anyList(), traceIdArg.capture());
        assertThat(message.getValue()).contains("【定时任务·早报】").contains("当前时间：");
        assertThat(finished.getTraceId()).isNotNull().isEqualTo(traceIdArg.getValue());

        // 收尾刷新：last_run_at + next_run_at（结束时刻重算）
        assertThat(fresh.getLastRunAt()).isNotNull();
        assertThat(fresh.getNextRunAt()).isNotNull();
        verify(taskRepository).save(fresh);

        // [M2] 成功后写站内通知（任务名 + 回答摘要 + 专属会话）
        verify(notificationService).notifyTaskResult("alice", "早报", "回答", 5L);
    }

    @Test
    void agentFailure_recordsFailedWithReadableError() {
        ScheduledTaskEntity task = task(3L, "alice");
        task.setConversationId(5L);
        ConversationEntity conv = new ConversationEntity();
        conv.setId(5L);
        conv.setUserId("alice");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conv));
        stubRunRowId();
        when(turnService.executeTurn(anyString(), eq(5L), anyString(), anyList(), anyString()))
                .thenThrow(new IllegalStateException("AI 服务暂时不可用（熔断中），请稍后再试"));

        runner.doRun(task);

        ArgumentCaptor<ScheduledTaskRunEntity> runs = ArgumentCaptor.forClass(ScheduledTaskRunEntity.class);
        verify(runRepository, times(2)).save(runs.capture());
        ScheduledTaskRunEntity finished = runs.getAllValues().get(1);
        assertThat(finished.getStatus()).isEqualTo(ScheduledTaskRunEntity.STATUS_FAILED);
        assertThat(finished.getErrorMessage()).contains("熔断");
        assertThat(finished.getAssistantMessageId()).isNull();
        // 失败不写通知
        verify(notificationService, never()).notifyTaskResult(anyString(), anyString(), any(), any());
    }

    @Test
    void conversationDeleted_recreatesAndBinds() {
        ScheduledTaskEntity task = task(3L, "alice");
        task.setConversationId(5L);
        when(conversationRepository.findById(5L)).thenReturn(Optional.empty());
        // 模拟 JPA save 语义：id 赋在被保存的实例上（生产代码用入参实例取 id）
        when(conversationRepository.save(any(ConversationEntity.class))).thenAnswer(inv -> {
            ConversationEntity c = inv.getArgument(0);
            c.setId(9L);
            return c;
        });
        stubRunRowId();
        when(turnService.executeTurn(eq("alice"), eq(9L), anyString(), anyList(), anyString()))
                .thenReturn(new AgentTurnService.TurnResult(new AgentClient.AgentChatResult(
                        "回答", null, "t", TraceData.empty()), 11L, 12L, "t"));

        runner.doRun(task);

        // 重建会话并定向回写绑定（避免整体 save 写回过期 next_run_at）
        verify(taskRepository).bindConversation(3L, 9L);
        verify(turnService).executeTurn(eq("alice"), eq(9L), anyString(), anyList(), anyString());
    }

    @Test
    void zombies_markedFailed() {
        ScheduledTaskEntity task = task(3L, "alice");
        ScheduledTaskRunEntity zombie = new ScheduledTaskRunEntity();
        zombie.setId(55L);
        zombie.setTaskId(3L);
        zombie.setStatus(ScheduledTaskRunEntity.STATUS_RUNNING);
        zombie.setStartedAt(LocalDateTime.now().minusHours(2));
        when(runRepository.findByTaskIdAndStatusAndStartedAtLessThan(
                eq(3L), eq(ScheduledTaskRunEntity.STATUS_RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(zombie));
        when(rateLimitService.isDailyBudgetExceeded("alice")).thenReturn(true);

        runner.doRun(task);

        assertThat(zombie.getStatus()).isEqualTo(ScheduledTaskRunEntity.STATUS_FAILED);
        assertThat(zombie.getErrorMessage()).contains("zombie");
        assertThat(zombie.getFinishedAt()).isNotNull();
    }

    // ==================== 投递/锁 ====================

    @Test
    void submit_lockHeld_returnsBusy() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(runner.submit(task(3L, "alice"))).isEqualTo(TaskRunner.SubmitResult.BUSY);
    }

    @Test
    void submit_poolRejected_releasesLockAndReturnsRejected() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 关闭执行池模拟拒绝（AbortPolicy）
        runner.shutdownExecutor();

        assertThat(runner.submit(task(3L, "alice"))).isEqualTo(TaskRunner.SubmitResult.REJECTED);
        verify(redis).delete("task:lock:3");
    }

    @Test
    void recordDispatchFailure_savesFailedRow() {
        runner.recordDispatchFailure(task(3L, "alice"), "执行池已满，本次跳过");

        ArgumentCaptor<ScheduledTaskRunEntity> runs = ArgumentCaptor.forClass(ScheduledTaskRunEntity.class);
        verify(runRepository).save(runs.capture());
        assertThat(runs.getValue().getStatus()).isEqualTo(ScheduledTaskRunEntity.STATUS_FAILED);
        assertThat(runs.getValue().getErrorMessage()).contains("执行池已满");
    }

    @Test
    void submit_redisDown_failsOpen() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        // 执行池关闭使投递必被拒绝：能走到「拒绝」说明锁检查 fail-open 放行（否则应 BUSY）
        runner.shutdownExecutor();

        assertThat(runner.submit(task(3L, "alice"))).isEqualTo(TaskRunner.SubmitResult.REJECTED);
    }
}