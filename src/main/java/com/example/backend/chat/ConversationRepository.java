package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    /** 用户的会话列表，最近活跃在前 */
    List<ConversationEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
