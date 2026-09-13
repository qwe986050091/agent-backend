package com.example.backend.feedback;

import com.example.backend.chat.ChatMessageEntity;
import com.example.backend.chat.ChatMessageRepository;
import com.example.backend.chat.ConversationEntity;
import com.example.backend.chat.ConversationRepository;
import com.example.backend.observability.BackendMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回答反馈业务（P2-11.1）：
 * 提交（upsert/互斥切换/再点取消）、历史批量水合、会话级联清理、管理员检索与统计。
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /** 管理后台问答全文展示上限（防超长回答撑爆页面） */
    private static final int ADMIN_CONTENT_LIMIT = 4000;
    /** 默认会话展示名（与 ConversationController 保持一致） */
    private static final String DEFAULT_CONVERSATION_TITLE = "默认会话";

    private final MessageFeedbackRepository feedbackRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ConversationRepository conversationRepository;
    private final BackendMetrics metrics;

    public FeedbackService(MessageFeedbackRepository feedbackRepository,
                           ChatMessageRepository chatMessageRepository,
                           ConversationRepository conversationRepository,
                           BackendMetrics metrics) {
        this.feedbackRepository = feedbackRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.conversationRepository = conversationRepository;
        this.metrics = metrics;
    }

    /**
     * 提交反馈（按 traceId+userId 唯一）。
     * 语义：rating=0 或与当前评价相同 → 删除（取消）；赞/踩互斥切换；踩切赞清空原因与备注。
     *
     * @return 提交后的有效评分（取消时为 0）
     */
    @Transactional
    public int submit(String userId, String traceId, Integer rating, String reasonCode, String comment) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        int r = rating == null ? 0 : rating;
        if (r != FeedbackReasons.RATING_UP && r != FeedbackReasons.RATING_DOWN
                && r != FeedbackReasons.RATING_CANCEL) {
            throw new IllegalArgumentException("rating 只能是 1（赞）、-1（踩）或 0（取消）");
        }
        String reason = normalizeReason(r, reasonCode);
        String note = normalizeComment(comment);

        // 归属校验：该回合必须存在属于本人的助手消息（被取消的回合无助手行，不允许反馈）
        ChatMessageEntity assistant = chatMessageRepository
                .findFirstByTraceIdAndUserIdAndRole(traceId, userId, "assistant")
                .orElseThrow(FeedbackTraceNotFoundException::new);

        MessageFeedbackEntity existing =
                feedbackRepository.findByTraceIdAndUserId(traceId, userId).orElse(null);

        // 取消：显式取消或重复点击当前已选评价
        if (r == FeedbackReasons.RATING_CANCEL
                || (existing != null && existing.getRating() == r)) {
            if (existing != null) {
                feedbackRepository.delete(existing);
                metrics.recordFeedback("cancel", null);
                log.info("[Feedback] 用户 {} 取消对回合 {} 的反馈", userId, traceId);
            }
            return 0;
        }

        if (existing == null) {
            existing = new MessageFeedbackEntity();
            existing.setTraceId(traceId);
            existing.setUserId(userId);
        }
        existing.setConversationId(assistant.getConversationId());
        existing.setRating(r);
        // 仅点踩保留原因/备注；赞一律清空，避免脏数据
        existing.setReasonCode(r == FeedbackReasons.RATING_DOWN ? reason : null);
        existing.setComment(r == FeedbackReasons.RATING_DOWN ? note : null);
        feedbackRepository.save(existing);
        metrics.recordFeedback(r == FeedbackReasons.RATING_UP ? "up" : "down", reason);
        log.info("[Feedback] 用户 {} 对回合 {} 评价 {}（原因={}）", userId, traceId, r, reason);
        return r;
    }

    /** 历史消息水合：traceId → 反馈视图（单条 IN 查询） */
    @Transactional(readOnly = true)
    public Map<String, FeedbackView> viewByTraceIds(String userId, Collection<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return Map.of();
        }
        return feedbackRepository.findByUserIdAndTraceIdIn(userId, traceIds).stream()
                .collect(Collectors.toMap(MessageFeedbackEntity::getTraceId, FeedbackView::of, (a, b) -> a));
    }

    /** 删除会话时级联清理反馈 */
    @Transactional
    public void deleteForConversation(String userId, Long conversationId) {
        feedbackRepository.deleteByUserIdAndConversationId(userId, conversationId);
    }

    /**
     * 管理员分页检索，并富化会话标题/问题/答案。
     */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> searchAdmin(Integer rating, String reason, String keyword,
                                                 LocalDateTime from, LocalDateTime to,
                                                 Pageable pageable) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String rc = reason == null || reason.isBlank() ? null : reason.trim();

        Page<MessageFeedbackEntity> page = feedbackRepository.search(rating, rc, kw, from, to, pageable);

        // 批量取本页回合的助手消息（单次 IN）
        List<String> traceIds = page.getContent().stream()
                .map(MessageFeedbackEntity::getTraceId)
                .toList();
        Map<String, ChatMessageEntity> answers = traceIds.isEmpty()
                ? Map.of()
                : chatMessageRepository.findByTraceIdInAndRole(traceIds, "assistant").stream()
                .collect(Collectors.toMap(ChatMessageEntity::getTraceId, Function.identity(), (a, b) -> a));

        return page.map(f -> toAdminRow(f, answers.get(f.getTraceId())));
    }

    /** 管理员统计：总数/赞/踩 + 点踩按原因分组 */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(LocalDateTime from, LocalDateTime to) {
        long up = 0;
        long down = 0;
        for (MessageFeedbackRepository.RatingCountProjection row : feedbackRepository.countByRating(from, to)) {
            if (row.getRating() != null && row.getRating() == FeedbackReasons.RATING_UP) {
                up = nz(row.getTotal());
            } else if (row.getRating() != null && row.getRating() == FeedbackReasons.RATING_DOWN) {
                down = nz(row.getTotal());
            }
        }

        List<Map<String, Object>> byReason = feedbackRepository.countDownByReason(from, to).stream()
                .map(row -> {
                    Map<String, Object> item = new HashMap<>();
                    String code = row.getReasonCode();
                    item.put("reasonCode", code);
                    item.put("reasonLabel", FeedbackReasons.labelOf(code));
                    item.put("count", nz(row.getTotal()));
                    return item;
                })
                .toList();

        Map<String, Object> resp = new HashMap<>();
        resp.put("total", up + down);
        resp.put("up", up);
        resp.put("down", down);
        resp.put("byReason", byReason);
        return resp;
    }

    /** 组装管理后台单行：反馈字段 + 会话标题 + 问题/答案全文（截断） */
    private Map<String, Object> toAdminRow(MessageFeedbackEntity f, ChatMessageEntity answer) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", f.getId());
        row.put("traceId", f.getTraceId());
        row.put("userId", f.getUserId());
        row.put("conversationId", f.getConversationId());
        row.put("conversationTitle", titleOf(f.getUserId(), f.getConversationId()));
        row.put("rating", f.getRating());
        row.put("reasonCode", f.getReasonCode());
        row.put("reasonLabel", FeedbackReasons.labelOf(f.getReasonCode()));
        row.put("comment", f.getComment());
        row.put("createdAt", f.getCreatedAt());
        row.put("updatedAt", f.getUpdatedAt());

        row.put("answer", answer == null ? null : clip(answer.getContent()));
        String question = null;
        if (answer != null) {
            // 同会话中助手消息之前最近一条用户消息 = 本回合提问
            Optional<ChatMessageEntity> q = chatMessageRepository
                    .findFirstByUserIdAndConversationIdAndRoleAndIdLessThanOrderByIdDesc(
                            f.getUserId(), f.getConversationId(), "user", answer.getId());
            question = q.map(ChatMessageEntity::getContent).map(FeedbackService::clip).orElse(null);
        }
        row.put("question", question);
        return row;
    }

    private String titleOf(String userId, Long conversationId) {
        if (conversationId == null || conversationId == 0L) {
            return DEFAULT_CONVERSATION_TITLE;
        }
        return conversationRepository.findById(conversationId)
                .filter(c -> c.getUserId().equals(userId))
                .map(ConversationEntity::getTitle)
                .orElse("（已删除会话）");
    }

    private static String normalizeReason(int rating, String reasonCode) {
        if (rating != FeedbackReasons.RATING_DOWN) {
            return null;
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }
        String code = reasonCode.trim();
        if (!FeedbackReasons.isValidCode(code)) {
            throw new IllegalArgumentException("非法的点踩原因码: " + code);
        }
        return code;
    }

    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String text = comment.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > FeedbackReasons.COMMENT_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "补充说明不能超过 " + FeedbackReasons.COMMENT_MAX_LENGTH + " 字");
        }
        return text;
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ADMIN_CONTENT_LIMIT ? text : text.substring(0, ADMIN_CONTENT_LIMIT) + "…";
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
