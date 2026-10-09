package com.example.backend.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecordEntity, Long> {

    /** 同一 traceId 至多一行（取消/错误兜底落库前去重用） */
    boolean existsByTraceId(String traceId);

    /**
     * 统计某用户自指定时间起（日预算场景传当天 00:00）的 total_tokens 合计。
     * 命中索引 idx_usage_user_created(user_id, created_at)，仅放行路径每回合一次轻量聚合。
     */
    @Query("""
            select coalesce(sum(u.totalTokens), 0)
            from LlmUsageRecordEntity u
            where u.userId = :userId and u.createdAt >= :from
            """)
    Long sumTotalTokensSince(@Param("userId") String userId, @Param("from") LocalDateTime from);

    Optional<LlmUsageRecordEntity> findByTraceId(String traceId);

    /** 总量聚合投影（Hibernate 对 Object[] 多聚合列在部分版本会拆成单值，用接口投影最稳） */
    interface TotalsProjection {
        Long getTurns();
        Long getInputTokens();
        Long getOutputTokens();
        Long getTotalTokens();
        java.math.BigDecimal getCostYuan();
    }

    interface ModelProjection {
        String getModel();
        Long getTurns();
        Long getInputTokens();
        Long getOutputTokens();
        Long getTotalTokens();
        java.math.BigDecimal getCostYuan();
    }

    interface ConversationProjection {
        Long getConversationId();
        String getTitle();
        Long getTurns();
        Long getTotalTokens();
        java.math.BigDecimal getCostYuan();
    }

    @Query("""
            select count(u) as turns,
                   coalesce(sum(u.inputTokens),0) as inputTokens,
                   coalesce(sum(u.outputTokens),0) as outputTokens,
                   coalesce(sum(u.totalTokens),0) as totalTokens,
                   coalesce(sum(u.costYuan),0) as costYuan
            from LlmUsageRecordEntity u
            where u.userId = :userId and u.createdAt >= :from and u.createdAt < :to
              and (:conversationId is null or u.conversationId = :conversationId)
            """)
    TotalsProjection totals(@Param("userId") String userId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to,
                            @Param("conversationId") Long conversationId);

    @Query("""
            select u.model as model, count(u) as turns,
                   coalesce(sum(u.inputTokens),0) as inputTokens,
                   coalesce(sum(u.outputTokens),0) as outputTokens,
                   coalesce(sum(u.totalTokens),0) as totalTokens,
                   coalesce(sum(u.costYuan),0) as costYuan
            from LlmUsageRecordEntity u
            where u.userId = :userId and u.createdAt >= :from and u.createdAt < :to
              and (:conversationId is null or u.conversationId = :conversationId)
            group by u.model
            order by coalesce(sum(u.totalTokens),0) desc
            """)
    List<ModelProjection> totalsByModel(@Param("userId") String userId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("conversationId") Long conversationId);

    @Query("""
            select u.conversationId as conversationId, c.title as title,
                   count(u) as turns,
                   coalesce(sum(u.totalTokens),0) as totalTokens,
                   coalesce(sum(u.costYuan),0) as costYuan
            from LlmUsageRecordEntity u
            left join ConversationEntity c
                   on c.id = u.conversationId and c.userId = u.userId
            where u.userId = :userId and u.createdAt >= :from and u.createdAt < :to
              and (:conversationId is null or u.conversationId = :conversationId)
            group by u.conversationId, c.title
            order by coalesce(sum(u.totalTokens),0) desc
            """)
    List<ConversationProjection> totalsByConversation(@Param("userId") String userId,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to,
                                                      @Param("conversationId") Long conversationId,
                                                      Pageable pageable);
}
