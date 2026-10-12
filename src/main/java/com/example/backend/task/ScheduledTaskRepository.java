package com.example.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, Long> {

    /** poller 扫到期任务（单轮上限 20，按到期时间升序） */
    List<ScheduledTaskEntity> findTop20ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            LocalDateTime now);

    List<ScheduledTaskEntity> findByUserIdOrderByIdDesc(String userId);

    Optional<ScheduledTaskEntity> findByIdAndUserId(Long id, String userId);

    /** 每用户启用任务数（创建/启用时的防呆校验） */
    long countByUserIdAndEnabledTrue(String userId);

    /**
     * 认领到期任务（DB CAS，多实例安全的主保险）：
     * 仅当 next_run_at 仍等于读取值时才推进——更新行数 1 表示本实例获得执行权。
     */
    @Modifying
    @Transactional
    @Query("update ScheduledTaskEntity t set t.nextRunAt = :newNext "
            + "where t.id = :id and t.nextRunAt = :oldNext")
    int claimNextRun(@Param("id") Long id,
                     @Param("oldNext") LocalDateTime oldNext,
                     @Param("newNext") LocalDateTime newNext);

    /**
     * 绑定专属会话（懒创建后回写）。
     * 用定向 UPDATE 而不是 save(实体)：执行中的实体 next_run_at 可能已被认领推进，
     * 整体 save 会把内存里的旧值写回（危险）。
     */
    @Modifying
    @Transactional
    @Query("update ScheduledTaskEntity t set t.conversationId = :conversationId where t.id = :id")
    int bindConversation(@Param("id") Long id, @Param("conversationId") Long conversationId);
}