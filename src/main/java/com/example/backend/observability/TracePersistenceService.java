package com.example.backend.observability;

import com.example.backend.agent.trace.RagData;
import com.example.backend.agent.trace.RagSegmentData;
import com.example.backend.agent.trace.SafetyData;
import com.example.backend.agent.trace.ToolData;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.agent.trace.UsageData;
import com.example.backend.billing.BillingService;
import com.example.backend.security.PromptSafetyEntity;
import com.example.backend.security.PromptSafetyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * trace 数据落库服务（P2-10）：把 agent 回传的用量/工具/召回写入三张审计表。
 *
 * 设计原则：
 *  - best-effort：任何落库异常只记日志，不影响对话主链路；
 *  - 幂等：同一 traceId 至多一行用量（uk_usage_trace）；取消兜底落库前先查存在性，
 *    防止完成回调与错误回调重复写。
 */
@Service
public class TracePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TracePersistenceService.class);

    private final LlmUsageRecordRepository usageRepository;
    private final ToolCallRecordRepository toolRepository;
    private final RagRecallRecordRepository ragRepository;
    private final BillingService billingService;
    private final PromptSafetyRepository promptSafetyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TracePersistenceService(LlmUsageRecordRepository usageRepository,
                                   ToolCallRecordRepository toolRepository,
                                   RagRecallRecordRepository ragRepository,
                                   BillingService billingService,
                                   PromptSafetyRepository promptSafetyRepository) {
        this.usageRepository = usageRepository;
        this.toolRepository = toolRepository;
        this.ragRepository = ragRepository;
        this.billingService = billingService;
        this.promptSafetyRepository = promptSafetyRepository;
    }

    /**
     * 回合正常结束后落库（用量一行 + 每个工具一行 + 每个召回片段一行）。
     * 已存在同 traceId 用量行则整体跳过（幂等）。
     */
    public void persistTurn(String traceId, String userId, Long conversationId,
                            Long assistantMessageId, TraceData traceData) {
        persist(traceId, userId, conversationId, assistantMessageId, traceData, false);
    }

    /**
     * 回合被前端取消（agent 未发 usage 或只发了部分）时，按已累积数据落库：
     * 用量行标 cancelled=1，已收到的工具/召回一并落。CB/5xx 等服务端故障不调用本方法。
     */
    public void persistCancelledTurn(String traceId, String userId, Long conversationId,
                                     Long assistantMessageId, TraceData traceData) {
        persist(traceId, userId, conversationId, assistantMessageId, traceData, true);
    }

    private void persist(String traceId, String userId, Long conversationId,
                         Long assistantMessageId, TraceData traceData, boolean forceCancelled) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            if (usageRepository.existsByTraceId(traceId)) {
                log.debug("[Trace] traceId={} 用量行已存在，跳过落库", traceId);
                return;
            }
            UsageData usage = traceData == null ? UsageData.empty()
                    : (traceData.usage() == null ? UsageData.empty() : traceData.usage());
            saveUsageRow(traceId, userId, conversationId, assistantMessageId, usage, forceCancelled);
            saveToolRows(traceId, userId, conversationId,
                    traceData == null ? List.of() : traceData.tools());
            saveRagRows(traceId, userId, conversationId,
                    traceData == null ? List.of() : traceData.recalls());
            saveSafetyRows(traceId, userId, conversationId,
                    traceData == null ? List.of() : traceData.safeties());
        } catch (Exception e) {
            // 审计落库失败不影响聊天主流程
            log.warn("[Trace] trace 落库失败（不影响聊天）traceId={}: {}", traceId, e.getMessage());
        }
    }

    /** 按 traceId 取已落库用量行（历史 trace 回查用） */
    public Optional<LlmUsageRecordEntity> findUsage(String traceId) {
        return usageRepository.findByTraceId(traceId);
    }

    public List<ToolCallRecordEntity> findTools(String traceId) {
        return toolRepository.findByTraceIdOrderByIdAsc(traceId);
    }

    public List<RagRecallRecordEntity> findRecalls(String traceId) {
        return ragRepository.findByTraceIdOrderByIdAsc(traceId);
    }

    // ==================== 内部方法 ====================

    private void saveUsageRow(String traceId, String userId, Long conversationId,
                              Long assistantMessageId, UsageData usage, boolean cancelled) {
        LlmUsageRecordEntity row = new LlmUsageRecordEntity();
        row.setTraceId(traceId);
        row.setUserId(userId);
        row.setConversationId(conversationId == null ? 0L : conversationId);
        row.setAssistantMessageId(assistantMessageId);
        row.setModel(usage.model());
        row.setInputTokens(usage.inputTokens());
        row.setOutputTokens(usage.outputTokens());
        row.setTotalTokens(usage.totalTokens() > 0
                ? usage.totalTokens() : usage.inputTokens() + usage.outputTokens());
        row.setCostYuan(billingService.cost(usage.model(), usage));
        row.setRounds(usage.rounds());
        row.setFallback(usage.fallback());
        row.setCancelled(cancelled || usage.cancelled());
        row.setDurationMs(usage.durationMs());
        row.setCreatedAt(LocalDateTime.now());
        usageRepository.save(row);
    }

    private void saveToolRows(String traceId, String userId, Long conversationId, List<ToolData> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        for (ToolData t : tools) {
            if (t == null || t.name() == null) {
                continue;
            }
            ToolCallRecordEntity row = new ToolCallRecordEntity();
            row.setTraceId(traceId);
            row.setUserId(userId);
            row.setConversationId(conversationId == null ? 0L : conversationId);
            row.setRoundSeq(t.round());
            row.setToolName(t.name());
            row.setArguments(t.arguments());
            row.setResultPreview(t.result());
            row.setSuccess(t.success());
            row.setErrorMessage(t.error());
            row.setDurationMs(t.durationMs());
            row.setCreatedAt(LocalDateTime.now());
            toolRepository.save(row);
        }
    }

    private void saveRagRows(String traceId, String userId, Long conversationId, List<RagData> recalls) {
        if (recalls == null || recalls.isEmpty()) {
            return;
        }
        int seq = 0;
        for (RagData rag : recalls) {
            if (rag == null) {
                continue;
            }
            // FR-4.2：无召回也落一行 query-only（score=0），与命中行同表可统计命中率
            if (rag.segments() == null || rag.segments().isEmpty()) {
                RagRecallRecordEntity row = new RagRecallRecordEntity();
                row.setTraceId(traceId);
                row.setUserId(userId);
                row.setConversationId(conversationId == null ? 0L : conversationId);
                row.setQueryText(rag.query());
                row.setScore(0d);
                row.setSeq(0);
                row.setCreatedAt(LocalDateTime.now());
                ragRepository.save(row);
                continue;
            }
            for (RagSegmentData seg : rag.segments()) {
                if (seg == null) {
                    continue;
                }
                RagRecallRecordEntity row = new RagRecallRecordEntity();
                row.setTraceId(traceId);
                row.setUserId(userId);
                row.setConversationId(conversationId == null ? 0L : conversationId);
                row.setQueryText(rag.query());
                row.setDocId(seg.docId());
                row.setSourceName(seg.source());
                row.setSourceType(seg.type());
                row.setSegmentText(seg.text());
                row.setScore(seg.score());
                row.setChannel(seg.channel());
                row.setRerankScore(seg.rerankScore());
                row.setSeq(seq++);
                row.setCreatedAt(LocalDateTime.now());
                ragRepository.save(row);
            }
        }
    }

    /** 内容安全检测行（P4-17）：一回合可多行（input/rag/tool_output/output 各通道） */
    private void saveSafetyRows(String traceId, String userId, Long conversationId,
                                List<SafetyData> safeties) {
        if (safeties == null || safeties.isEmpty()) {
            return;
        }
        for (SafetyData s : safeties) {
            if (s == null || s.channel() == null) {
                continue;
            }
            PromptSafetyEntity row = new PromptSafetyEntity();
            row.setTraceId(traceId);
            row.setUserId(userId == null ? "" : userId);
            row.setConversationId(conversationId == null ? 0L : conversationId);
            row.setChannel(s.channel());
            row.setRuleHits(ruleHitsJson(s));
            row.setScore(s.score());
            row.setVerdict(blankTo(s.verdict(), "benign"));
            row.setDetector(blankTo(s.detector(), "rule"));
            row.setAction(blankTo(s.action(), "none"));
            row.setLlmReason(truncate(s.llmReason(), 500));
            row.setExcerpt(truncate(s.excerpt(), 500));
            row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, s.latencyMs())));
            row.setCreatedAt(LocalDateTime.now());
            promptSafetyRepository.save(row);
        }
    }

    /**
     * 流开始前被 agent 安全闸门拒绝（400 SAFETY_BLOCKED）时补录一条审计行。
     * 该路径没有 agent trace 数据，只有 backend 侧已知的原文片段；
     * 分数/命中明细以 agent 日志为准（block 模式下必 ≥ block 阈值）。
     */
    public void recordInputBlock(String traceId, String userId, Long conversationId, String message) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            PromptSafetyEntity row = new PromptSafetyEntity();
            row.setTraceId(traceId);
            row.setUserId(userId == null ? "" : userId);
            row.setConversationId(conversationId == null ? 0L : conversationId);
            row.setChannel("input");
            row.setScore(0);
            row.setVerdict("malicious");
            row.setDetector("rule");
            row.setAction("block");
            row.setExcerpt(truncate(message, 500));
            row.setCreatedAt(LocalDateTime.now());
            promptSafetyRepository.save(row);
        } catch (Exception e) {
            // 审计失败不影响对话主流程
            log.warn("[Trace] 安全拦截审计落库失败（不影响对话）traceId={}: {}", traceId, e.getMessage());
        }
    }

    /** 按 traceId 取安全检测记录（P4-16 审计后台/复盘用） */
    public List<PromptSafetyEntity> findSafeties(String traceId) {
        return promptSafetyRepository.findByTraceIdOrderByIdAsc(traceId);
    }

    private String ruleHitsJson(SafetyData s) {
        if (s.ruleHits() == null || s.ruleHits().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(s.ruleHits());
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
