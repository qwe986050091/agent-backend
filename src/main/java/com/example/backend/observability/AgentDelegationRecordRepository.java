package com.example.backend.observability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentDelegationRecordRepository
        extends JpaRepository<AgentDelegationRecordEntity, Long> {

    List<AgentDelegationRecordEntity> findByTraceIdOrderByIdAsc(String traceId);
}
