package com.example.backend.eval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalCandidateRepository extends JpaRepository<EvalCandidateEntity, Long> {

    Optional<EvalCandidateEntity> findByTraceIdAndUserId(String traceId, String userId);

    boolean existsByTraceIdAndUserId(String traceId, String userId);

    List<EvalCandidateEntity> findByStatusOrderByIdDesc(String status);

    long countByStatus(String status);
}
