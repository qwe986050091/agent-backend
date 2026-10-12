package com.example.backend.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {

    /**
     * 游标拉取：id 大于 afterId 的通知（升序 = 事件先后顺序）。
     * 前端记住已消费的最大 id，刷新/离线后可从上次位置补齐（通知不丢）。
     */
    List<UserNotificationEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(
            String userId, Long afterId, Pageable pageable);

    /** 未读数（个人红点） */
    long countByUserIdAndReadAtIsNull(String userId);

    Optional<UserNotificationEntity> findByIdAndUserId(Long id, String userId);
}