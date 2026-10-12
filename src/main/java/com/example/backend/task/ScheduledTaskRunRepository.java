package com.example.backend.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, Long> {

    /** 执行记录分页（按 id 倒序 = 最近在前） */
    List<ScheduledTaskRunEntity> findByTaskIdOrderByIdDesc(Long taskId, Pageable pageable);

    /** 当日（00:00 起）执行次数：每日上限口径（含 run-now 与 skipped 行，简化且安全） */
    long countByTaskIdAndCreatedAtGreaterThanEqual(Long taskId, LocalDateTime from);

    /** 僵尸行：同任务 running 且开始时间早于 cutoff（进程被杀/崩溃残留） */
    List<ScheduledTaskRunEntity> findByTaskIdAndStatusAndStartedAtLessThan(
            Long taskId, String status, LocalDateTime cutoff);

    /** 列表页「上次结果」 */
    Optional<ScheduledTaskRunEntity> findFirstByTaskIdOrderByIdDesc(Long taskId);

    /** 删除任务时连带清理执行记录（专属会话与消息保留） */
    void deleteByTaskId(Long taskId);
}