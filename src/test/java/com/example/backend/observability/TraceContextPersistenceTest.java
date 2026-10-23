package com.example.backend.observability;

import com.example.backend.agent.trace.ContextData;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.agent.trace.UsageData;
import com.example.backend.billing.BillingService;
import com.example.backend.security.PromptSafetyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上下文构成落库与历史回查单测（P1-D）。
 *
 * 覆盖：
 * 1. 回合结束时 context 序列化进 llm_usage_record.context_json；
 * 2. 历史 trace 回查能把 JSON 还原为 ContextData；
 * 3. 老数据（null）与损坏 JSON 均返回 null，不影响回查主流程。
 */
class TraceContextPersistenceTest {

    private LlmUsageRecordRepository usageRepository;
    private BillingService billingService;
    private TracePersistenceService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(LlmUsageRecordRepository.class);
        billingService = mock(BillingService.class);
        service = new TracePersistenceService(
                usageRepository,
                mock(ToolCallRecordRepository.class),
                mock(RagRecallRecordRepository.class),
                mock(AgentDelegationRecordRepository.class),
                billingService,
                mock(PromptSafetyRepository.class));
    }

    private static ContextData contextData() {
        return new ContextData("balanced", 10000, 320, false, List.of(
                new ContextData.Segment("system", "系统提示（含安全边界）", 2500, 120, false, "受保护"),
                new ContextData.Segment("history", "历史对话（含本轮）", 400, 200, true, "保留 2 条，裁剪 1 条")));
    }

    @Test
    void persistTurnSerializesContextIntoUsageRow() {
        when(usageRepository.existsByTraceId("t-p1d")).thenReturn(false);
        when(billingService.cost(any(), any())).thenReturn(BigDecimal.ZERO);
        TraceData trace = new TraceData(UsageData.empty(), List.of(), List.of(), List.of(), List.of(),
                contextData());

        service.persistTurn("t-p1d", "u1", 1L, 2L, trace);

        ArgumentCaptor<LlmUsageRecordEntity> captor = ArgumentCaptor.forClass(LlmUsageRecordEntity.class);
        verify(usageRepository).save(captor.capture());
        String json = captor.getValue().getContextJson();
        assertThat(json).contains("\"strategy\":\"balanced\"").contains("\"key\":\"history\"")
                .contains("\"tokens\":200");
    }

    @Test
    void persistTurnLeavesContextNullWhenAgentDidNotSendIt() {
        when(usageRepository.existsByTraceId("t-old")).thenReturn(false);
        when(billingService.cost(any(), any())).thenReturn(BigDecimal.ZERO);
        TraceData trace = new TraceData(UsageData.empty(), List.of(), List.of(), List.of(), List.of(), null);

        service.persistTurn("t-old", "u1", 1L, 2L, trace);

        ArgumentCaptor<LlmUsageRecordEntity> captor = ArgumentCaptor.forClass(LlmUsageRecordEntity.class);
        verify(usageRepository).save(captor.capture());
        assertThat(captor.getValue().getContextJson()).isNull();
    }

    @Test
    void findContextRestoresPersistedJson() {
        LlmUsageRecordEntity row = new LlmUsageRecordEntity();
        row.setContextJson("{\"strategy\":\"history-priority\",\"totalBudgetTokens\":10000,"
                + "\"totalTokens\":500,\"overBudget\":false,\"segments\":"
                + "[{\"key\":\"summary\",\"label\":\"历史摘要\",\"budgetTokens\":1200,"
                + "\"tokens\":300,\"truncated\":true,\"detail\":\"超出预算，已裁剪\"}]}");
        when(usageRepository.findByTraceId("t-p1d")).thenReturn(Optional.of(row));

        ContextData context = service.findContext("t-p1d");

        assertThat(context).isNotNull();
        assertThat(context.strategy()).isEqualTo("history-priority");
        assertThat(context.segments()).hasSize(1);
        assertThat(context.segments().get(0).truncated()).isTrue();
    }

    @Test
    void findContextReturnsNullForMissingOrBrokenJson() {
        LlmUsageRecordEntity empty = new LlmUsageRecordEntity();
        when(usageRepository.findByTraceId("t-empty")).thenReturn(Optional.of(empty));
        assertThat(service.findContext("t-empty")).isNull();

        LlmUsageRecordEntity broken = new LlmUsageRecordEntity();
        broken.setContextJson("{不是 JSON");
        when(usageRepository.findByTraceId("t-broken")).thenReturn(Optional.of(broken));
        assertThat(service.findContext("t-broken")).isNull();

        when(usageRepository.findByTraceId("t-missing")).thenReturn(Optional.empty());
        assertThat(service.findContext("t-missing")).isNull();
    }
}
