package com.example.backend.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 内容安全检测记录仓储（P4-17）。
 * 写入走 {@link com.example.backend.observability.TracePersistenceService}；
 * findByTraceId 供 P4-16 审计后台与问题复盘使用。
 */
public interface PromptSafetyRepository extends JpaRepository<PromptSafetyEntity, Long> {

    List<PromptSafetyEntity> findByTraceIdOrderByIdAsc(String traceId);
}