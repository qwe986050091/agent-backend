package com.example.backend.observability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolCallRecordRepository extends JpaRepository<ToolCallRecordEntity, Long> {

    List<ToolCallRecordEntity> findByTraceIdOrderByIdAsc(String traceId);

    List<ToolCallRecordEntity> findByUserIdOrderByIdDesc(String userId);
}
