package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByUserIdOrderByCreatedAtAsc(String userId);

    /**
     * 取该用户最近 30 条消息（按 id 倒序），调用方需反转为升序。
     * 用于无状态化后每次对话向 agent 重建请求级记忆。
     */
    List<ChatMessageEntity> findTop30ByUserIdOrderByIdDesc(String userId);
}
