package com.example.backend.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageFeedbackRepository extends JpaRepository<MessageFeedbackEntity, Long> {

    /** 取某用户对某回合的反馈（唯一键保证至多一条） */
    Optional<MessageFeedbackEntity> findByTraceIdAndUserId(String traceId, String userId);

    /** 历史消息水合：批量取某用户一批 traceId 的反馈（单次 IN 查询，无 N+1） */
    List<MessageFeedbackEntity> findByUserIdAndTraceIdIn(String userId, Collection<String> traceIds);

    /** 删除会话时级联清理其反馈 */
    void deleteByUserIdAndConversationId(String userId, Long conversationId);

    /**
     * 管理员分页检索（createdAt 倒序）。
     * keyword 同时匹配反馈备注与该回合问答内容（同 traceId 的 chat_message）。
     * 各过滤参数为 null 时该条件不生效。
     */
    @Query("""
            select f from MessageFeedbackEntity f
            where (:rating is null or f.rating = :rating)
              and (:reason is null or f.reasonCode = :reason)
              and (
                :keyword is null
                or f.comment like concat('%', :keyword, '%')
                or exists (select 1 from com.example.backend.chat.ChatMessageEntity m
                           where m.traceId = f.traceId
                             and m.content like concat('%', :keyword, '%'))
              )
              and (:from is null or f.createdAt >= :from)
              and (:to is null or f.createdAt <= :to)
            order by f.createdAt desc, f.id desc
            """)
    Page<MessageFeedbackEntity> search(@Param("rating") Integer rating,
                                       @Param("reason") String reason,
                                       @Param("keyword") String keyword,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /** 统计：按评价分组计数（时间范围可选） */
    @Query("""
            select f.rating as rating, count(f) as total
            from MessageFeedbackEntity f
            where (:from is null or f.createdAt >= :from)
              and (:to is null or f.createdAt <= :to)
            group by f.rating
            """)
    List<RatingCountProjection> countByRating(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    /** 统计：点踩按原因码分组计数（时间范围可选） */
    @Query("""
            select f.reasonCode as reasonCode, count(f) as total
            from MessageFeedbackEntity f
            where f.rating = -1
              and (:from is null or f.createdAt >= :from)
              and (:to is null or f.createdAt <= :to)
            group by f.reasonCode
            """)
    List<ReasonCountProjection> countDownByReason(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    /** 投影：按评分计数 */
    interface RatingCountProjection {
        Integer getRating();
        Long getTotal();
    }

    /** 投影：按原因码计数（reasonCode 可能为 null） */
    interface ReasonCountProjection {
        String getReasonCode();
        Long getTotal();
    }
}
