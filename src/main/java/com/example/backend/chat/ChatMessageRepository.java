package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** 某用户某会话的全部消息（时间升序） */
    List<ChatMessageEntity> findByUserIdAndConversationIdOrderByCreatedAtAsc(String userId, Long conversationId);

    /**
     * 取某用户某会话最近 30 条消息（按 id 倒序），调用方需反转为升序。
     * 用于无状态化后每次对话向 agent 重建请求级记忆。
     */
    List<ChatMessageEntity> findTop30ByUserIdAndConversationIdOrderByIdDesc(String userId, Long conversationId);

    /** 某用户某会话是否已有消息（判断默认会话是否需要展示） */
    boolean existsByUserIdAndConversationId(String userId, Long conversationId);

    /** trace 归属校验：某条 traceId 的消息是否属于该用户（历史 trace 回查鉴权） */
    boolean existsByTraceIdAndUserId(String traceId, String userId);

    /** 删除会话时级联删除其全部消息 */
    void deleteByUserIdAndConversationId(String userId, Long conversationId);
}
