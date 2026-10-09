package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, Long> {

    /** 批量取一批消息的附件（历史展示 + 向 agent 重建带图历史），结果无序，调用方按 messageId 分组 */
    List<ChatAttachmentEntity> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    /** 鉴权读取：只能取本人附件 */
    Optional<ChatAttachmentEntity> findByIdAndUserId(Long id, String userId);

    /** 删除会话时按消息批量清理附件元数据（文件由 ChatAttachmentService 同步删除） */
    List<ChatAttachmentEntity> findByMessageIdIn(Collection<Long> messageIds);
}
