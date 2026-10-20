package com.example.backend.eval;

import com.example.backend.chat.ChatMessageEntity;
import com.example.backend.chat.ChatMessageRepository;
import com.example.backend.feedback.MessageFeedbackEntity;
import com.example.backend.feedback.MessageFeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈反哺评测集（P0-B）：把「点踩」的回合提炼成候选用例，人工确认后再并入评测数据集。
 *
 * <p>闭环意义：「线上真实不满 → 变成本地可复现的评测用例」——这是 AI 评测体系从 0 到 1 的关键一环
 * （对标淘天「评测 0→1」、腾讯「回归 Harness」）。
 *
 * <p>纯 DB 读写，不调 LLM。
 */
@Service
public class EvalCandidateService {

    private static final Logger log = LoggerFactory.getLogger(EvalCandidateService.class);

    /** 单次扫描的点踩反馈上限（防一次拉爆内存） */
    private static final int SCAN_LIMIT = 200;
    private static final int ANSWER_CLIP = 2000;

    private final EvalCandidateRepository candidateRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public EvalCandidateService(EvalCandidateRepository candidateRepository,
                                MessageFeedbackRepository feedbackRepository,
                                ChatMessageRepository chatMessageRepository,
                                ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.feedbackRepository = feedbackRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描点踩反馈，生成新的候选用例（幂等：已存在的 traceId+userId 跳过）。
     *
     * @return 本次新增的候选数
     */
    @Transactional
    public int refresh() {
        List<MessageFeedbackEntity> downs = feedbackRepository
                .search(-1, null, null, null, null, PageRequest.of(0, SCAN_LIMIT))
                .getContent();
        int created = 0;
        for (MessageFeedbackEntity f : downs) {
            if (candidateRepository.existsByTraceIdAndUserId(f.getTraceId(), f.getUserId())) {
                continue;
            }
            candidateRepository.save(build(f));
            created++;
        }
        log.info("[Eval] 反馈反哺刷新：扫描 {} 条点踩，新增 {} 条候选用例", downs.size(), created);
        return created;
    }

    private EvalCandidateEntity build(MessageFeedbackEntity f) {
        EvalCandidateEntity c = new EvalCandidateEntity();
        c.setSource("feedback");
        c.setTraceId(f.getTraceId());
        c.setUserId(f.getUserId());
        c.setReasonCode(f.getReasonCode());
        c.setComment(f.getComment());

        ChatMessageEntity answer = chatMessageRepository
                .findFirstByTraceIdAndUserIdAndRole(f.getTraceId(), f.getUserId(), "assistant")
                .orElse(null);
        if (answer != null) {
            c.setAnswer(clip(answer.getContent(), ANSWER_CLIP));
            c.setQuestion(chatMessageRepository
                    .findFirstByUserIdAndConversationIdAndRoleAndIdLessThanOrderByIdDesc(
                            f.getUserId(), answer.getConversationId(), "user", answer.getId())
                    .map(ChatMessageEntity::getContent)
                    .map(q -> clip(q, ANSWER_CLIP))
                    .orElse(null));
        }
        c.setSuggestedCategory(suggestCategory(f.getReasonCode(), c.getQuestion()));
        c.setSuggestedPoints(suggestPoints(f.getComment()));
        c.setStatus(EvalCandidateEntity.STATUS_NEW);
        return c;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String st = (status == null || status.isBlank()) ? EvalCandidateEntity.STATUS_NEW : status.trim();
        return candidateRepository.findByStatusOrderByIdDesc(st).stream().map(EvalCandidateService::toRow).toList();
    }

    /**
     * 采纳：标记为 promoted，并返回一份可直接粘进评测数据集的用例 JSON 草稿。
     */
    @Transactional
    public Map<String, Object> promote(Long id) {
        EvalCandidateEntity c = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("候选用例不存在: " + id));
        c.setStatus(EvalCandidateEntity.STATUS_PROMOTED);
        candidateRepository.save(c);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", "fb-" + shortId(c.getTraceId()));
        draft.put("category", c.getSuggestedCategory());
        draft.put("input", c.getQuestion() == null ? "（原回合未取到提问，请手工补全）" : c.getQuestion());
        draft.put("requiredPoints", splitPoints(c.getSuggestedPoints()));
        draft.put("tags", List.of("feedback", "regression"));
        draft.put("note", "来源反馈：" + (c.getReasonCode() == null ? "点踩" : c.getReasonCode())
                + (c.getComment() == null ? "" : " / " + c.getComment()));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("status", c.getStatus());
        try {
            resp.put("caseDraft", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft));
        } catch (Exception e) {
            resp.put("caseDraft", draft.toString());
        }
        return resp;
    }

    @Transactional
    public Map<String, Object> dismiss(Long id) {
        EvalCandidateEntity c = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("候选用例不存在: " + id));
        c.setStatus(EvalCandidateEntity.STATUS_DISMISSED);
        candidateRepository.save(c);
        return Map.of("id", id, "status", c.getStatus());
    }

    // ==================== 纯函数（可单测） ====================

    /** 建议归类：涉及知识库/文档/政策类问题 → rag_qa，否则 plain */
    static String suggestCategory(String reasonCode, String question) {
        if ("factual_error".equalsIgnoreCase(reasonCode)) {
            return "rag_qa";
        }
        String q = question == null ? "" : question;
        if (q.contains("知识库") || q.contains("文档") || q.contains("政策") || q.contains("规则")) {
            return "rag_qa";
        }
        return "plain";
    }

    /** 从点踩备注里粗提候选要点：按标点切分，取长度 2~20 的前 3 段 */
    static String suggestPoints(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String seg : comment.split("[，,。；;、\\n]+")) {
            String t = seg.trim();
            if (t.length() >= 2 && t.length() <= 20) {
                parts.add(t);
            }
            if (parts.size() >= 3) {
                break;
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static List<String> splitPoints(String csv) {
        return (csv == null || csv.isBlank()) ? List.of() : List.of(csv.split(","));
    }

    private static Map<String, Object> toRow(EvalCandidateEntity c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("source", c.getSource());
        row.put("traceId", c.getTraceId());
        row.put("userId", c.getUserId());
        row.put("question", c.getQuestion());
        row.put("answer", c.getAnswer());
        row.put("reasonCode", c.getReasonCode());
        row.put("comment", c.getComment());
        row.put("suggestedCategory", c.getSuggestedCategory());
        row.put("suggestedPoints", splitPoints(c.getSuggestedPoints()));
        row.put("status", c.getStatus());
        row.put("createdAt", c.getCreatedAt());
        return row;
    }

    private static String shortId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "unknown";
        }
        return traceId.length() <= 8 ? traceId : traceId.substring(0, 8);
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
