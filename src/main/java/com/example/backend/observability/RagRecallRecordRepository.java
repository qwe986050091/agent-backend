package com.example.backend.observability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagRecallRecordRepository extends JpaRepository<RagRecallRecordEntity, Long> {

    List<RagRecallRecordEntity> findByTraceIdOrderByIdAsc(String traceId);
}
