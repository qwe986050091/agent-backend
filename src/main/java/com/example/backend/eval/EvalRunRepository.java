package com.example.backend.eval;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalRunRepository extends JpaRepository<EvalRunEntity, Long> {

    Optional<EvalRunEntity> findByRunId(String runId);

    Page<EvalRunEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 指标趋势：最近 N 次运行（倒序） */
    List<EvalRunEntity> findTop30ByOrderByIdDesc();
}
