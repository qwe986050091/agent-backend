package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.approval.ToolGrantStore;
import com.example.backend.observability.TracePersistenceService;
import com.example.backend.skill.SkillRepository;
import com.example.backend.skill.UserSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentTurnService} 单元测试（M1a 抽取契约）：
 * payload 授权并集、回合落库顺序、agent 失败时用户消息保留但助手消息不落。
 */
@ExtendWith(MockitoExtension.class)
class AgentTurnServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private AgentMemoryRepository agentMemoryRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private UserSkillRepository userSkillRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private ChatAttachmentService attachmentService;
    @Mock private ToolGrantStore toolGrantStore;
    @Mock private AgentClient agentClient;
    @Mock private TracePersistenceService tracePersistenceService;

    private AgentTurnService service;

    @BeforeEach
    void setUp() {
        service = new AgentTurnService(chatMessageRepository, agentMemoryRepository,
                conversationRepository, userSkillRepository, skillRepository,
                attachmentService, toolGrantStore, agentClient, tracePersistenceService,
                new ObjectMapper());
    }

    /** 复用既有空历史装配（无摘要、无历史、无技能挂载） */
    private void stubEmptyState() {
        when(agentMemoryRepository.findById(any(AgentMemoryId.class))).thenReturn(Optional.empty());
        when(chatMessageRepository.findTop30ByUserIdAndConversationIdOrderByIdDesc(anyString(), anyLong()))
                .thenReturn(List.of());
        when(userSkillRepository.findByUserId("alice")).thenReturn(List.of());
    }

    private void stubSaveAssignsIds() {
        AtomicLong seq = new AtomicLong(100);
        when(chatMessageRepository.save(any(ChatMessageEntity.class))).thenAnswer(inv -> {
            ChatMessageEntity e = inv.getArgument(0);
            e.setId(seq.incrementAndGet());
            return e;
        });
    }

    @Test
    void executeTurn_unionsGrantAndExtraTools_persistsTurnAndSummary() {
        stubEmptyState();
        stubSaveAssignsIds();
        when(toolGrantStore.list("alice", 7L)).thenReturn(Set.of("web_search"));
        when(agentClient.chat(any())).thenReturn(new AgentClient.AgentChatResult(
                "回答", "新摘要", "trace-agent", TraceData.empty()));
        ConversationEntity conv = new ConversationEntity();
        conv.setUserId("alice");
        conv.setTitle("会话");
        when(conversationRepository.findById(7L)).thenReturn(Optional.of(conv));

        AgentTurnService.TurnResult result =
                service.executeTurn("alice", 7L, "你好", List.of("send_email", " "), "trace-req");

        // payload：会话级授权 ∪ 任务预授权（空白项忽略）
        ArgumentCaptor<AgentClient.AgentChatPayload> payload =
                ArgumentCaptor.forClass(AgentClient.AgentChatPayload.class);
        verify(agentClient).chat(payload.capture());
        assertThat(payload.getValue().allowedTools())
                .containsExactlyInAnyOrder("web_search", "send_email");
        assertThat(payload.getValue().message()).isEqualTo("你好");

        // 落库顺序：user(101) → assistant(102)；审计用 agent 回传的 traceId
        assertThat(result.userMessageId()).isEqualTo(101L);
        assertThat(result.assistantMessageId()).isEqualTo(102L);
        assertThat(result.traceId()).isEqualTo("trace-agent");
        verify(tracePersistenceService).persistTurn(
                eq("trace-agent"), eq("alice"), eq(7L), eq(102L), any(TraceData.class));
        // 摘要落库（agent 回传新摘要）+ 会话活跃时间刷新
        verify(agentMemoryRepository).save(any(AgentMemoryEntity.class));
        assertThat(conv.getUpdatedAt()).isNotNull();
    }

    @Test
    void executeTurn_withoutAgentTraceId_fallsBackToRequestTraceId() {
        stubEmptyState();
        stubSaveAssignsIds();
        when(toolGrantStore.list("alice", 7L)).thenReturn(Set.of());
        when(agentClient.chat(any())).thenReturn(new AgentClient.AgentChatResult(
                "回答", null, null, TraceData.empty()));
        when(conversationRepository.findById(7L)).thenReturn(Optional.empty());

        AgentTurnService.TurnResult result =
                service.executeTurn("alice", 7L, "你好", List.of(), "trace-req");

        assertThat(result.traceId()).isEqualTo("trace-req");
        verify(tracePersistenceService).persistTurn(
                eq("trace-req"), eq("alice"), eq(7L), eq(102L), any(TraceData.class));
        // 无摘要回传：跳过摘要落库；会话不存在：跳过刷新
        verify(agentMemoryRepository, never()).save(any(AgentMemoryEntity.class));
        verify(conversationRepository, never()).save(any(ConversationEntity.class));
    }

    @Test
    void executeTurn_agentFailure_propagatesWithUserMessageKept() {
        stubEmptyState();
        stubSaveAssignsIds();
        when(toolGrantStore.list("alice", 7L)).thenReturn(Set.of());
        when(agentClient.chat(any())).thenThrow(new IllegalStateException("AI 服务暂时不可用（熔断中）"));

        assertThatThrownBy(() -> service.executeTurn("alice", 7L, "你好", List.of(), "t1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("熔断");

        // 用户消息已落库（与手动对话一致），助手消息/审计不落
        verify(chatMessageRepository).save(any(ChatMessageEntity.class));
        verify(tracePersistenceService, never()).persistTurn(anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void executeTurn_emptyConversationId_skipsTouch() {
        stubEmptyState();
        stubSaveAssignsIds();
        when(toolGrantStore.list("alice", 0L)).thenReturn(Set.of());
        when(agentClient.chat(any())).thenReturn(new AgentClient.AgentChatResult(
                "回答", null, "t2", TraceData.empty()));

        service.executeTurn("alice", 0L, "你好", List.of(), "t1");

        // 默认会话（0）不建表记录：不做会话查询/刷新
        verify(conversationRepository, never()).findById(anyLong());
    }
}