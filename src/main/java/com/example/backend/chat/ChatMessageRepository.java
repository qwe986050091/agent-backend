package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /** 取某用户某回合（traceId）的指定角色消息（反馈归属校验/富化） */
    Optional<ChatMessageEntity> findFirstByTraceIdAndUserIdAndRole(String traceId, String userId, String role);

    /** 按 traceId 取指定角色消息（管理后台跨用户富化；traceId 全局唯一） */
    Optional<ChatMessageEntity> findFirstByTraceIdAndRole(String traceId, String role);

    /** 管理后台富化：按一批 traceId 取指定角色消息（单次 IN） */
    List<ChatMessageEntity> findByTraceIdInAndRole(Collection<String> traceIds, String role);

    /** 取某用户某会话中、id 小于指定消息的最近一条消息（管理后台定位回答对应的提问） */
    Optional<ChatMessageEntity> findFirstByUserIdAndConversationIdAndRoleAndIdLessThanOrderByIdDesc(
            String userId, Long conversationId, String role, Long id);

    /** 删除会话时级联删除其全部消息 */
    void deleteByUserIdAndConversationId(String userId, Long conversationId);
}
