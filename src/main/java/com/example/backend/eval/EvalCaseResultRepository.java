package com.example.backend.eval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalCaseResultRepository extends JpaRepository<EvalCaseResultEntity, Long> {

    List<EvalCaseResultEntity> findByRunIdOrderByIdAsc(String runId);

    List<EvalCaseResultEntity> findByRunIdAndPassed(String runId, boolean passed);

    void deleteByRunId(String runId);
}
