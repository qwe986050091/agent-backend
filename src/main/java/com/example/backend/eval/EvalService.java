package com.example.backend.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 评测业务（P0-B）：上报落库、运行列表/详情、指标趋势、两次运行对比。
 *
 * <p>本服务<b>不调用任何 LLM</b>——只做纯 DB 读写，符合 backend「必须纯净、只经 HTTP 调 agent」的硬约束；
 * 评测执行与评测判断都在 agent 仓完成，这里仅承载结果与看板数据。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int ANSWER_LIMIT = 1000;
    private static final int CSV_LIMIT = 500;

    private final EvalRunRepository runRepository;
    private final EvalCaseResultRepository caseResultRepository;
    private final EvalCandidateRepository candidateRepository;
    private final ObjectMapper objectMapper;

    public EvalService(EvalRunRepository runRepository,
                       EvalCaseResultRepository caseResultRepository,
                       EvalCandidateRepository candidateRepository,
                       ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.candidateRepository = candidateRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 上报落库 ====================

    /**
     * 落库一次评测运行（幂等：同 runId 覆盖旧记录与其逐例结果）。
     */
    @Transactional
    public Map<String, Object> ingest(EvalReportPayload payload) {
        if (payload == null || payload.runId() == null || payload.runId().isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        EvalReportPayload.Metrics m = payload.metrics();
        EvalRunEntity run = runRepository.findByRunId(payload.runId()).orElseGet(EvalRunEntity::new);
        run.setRunId(payload.runId());
        run.setLabel(payload.label());
        run.setDatasetName(payload.datasetName());
        run.setMode(payload.mode());
        run.setModel(payload.model());
        run.setJudgeEnabled(payload.judgeEnabled());
        if (m != null) {
            run.setTotal(m.total());
            run.setPassed(m.passed());
            run.setFailed(m.failed());
            run.setPassRate(m.passRate());
            run.setToolAccuracy(m.toolAccuracy());
            run.setCitationAccuracy(m.citationAccuracy());
            run.setPointCoverage(m.pointCoverage());
            run.setRefusalRate(m.refusalRate());
            run.setAvgTokens(m.avgTokens());
            run.setAvgDurationMs(m.avgDurationMs());
        }
        run.setGeneratedAt(parseTime(payload.generatedAt()));
        EvalRunEntity saved = runRepository.save(run);

        // 幂等：先清旧逐例结果再写新
        caseResultRepository.deleteByRunId(payload.runId());
        List<EvalCaseResultEntity> rows = new ArrayList<>();
        if (payload.results() != null) {
            for (EvalReportPayload.CaseResult c : payload.results()) {
                rows.add(toCaseResultEntity(payload.runId(), c));
            }
        }
        caseResultRepository.saveAll(rows);

        log.info("[Eval] 上报落库 runId={} label={} {}/{} 通过", payload.runId(), payload.label(),
                run.getPassed(), run.getTotal());
        return Map.of("runId", payload.runId(), "stored", rows.size());
    }

    private EvalCaseResultEntity toCaseResultEntity(String runId, EvalReportPayload.CaseResult c) {
        EvalCaseResultEntity e = new EvalCaseResultEntity();
        e.setRunId(runId);
        e.setCaseId(c.id());
        e.setCategory(c.category());
        e.setPassed(c.passed());
        if (c.checks() != null) {
            e.setFailedChecks(csv(c.checks().stream().filter(x -> !x.passed())
                    .map(EvalReportPayload.Check::name).toList(), 300));
            try {
                e.setChecksJson(objectMapper.writeValueAsString(c.checks()));
            } catch (Exception ex) {
                // 明细序列化失败不影响落库主体
            }
        }
        e.setAnswerExcerpt(clip(c.answer(), ANSWER_LIMIT));
        e.setActualTools(csv(c.actualTools(), CSV_LIMIT));
        e.setCitedDocs(csv(c.citedDocs(), CSV_LIMIT));
        e.setRetrievedDocs(csv(c.retrievedDocs(), CSV_LIMIT));
        e.setTokens(c.tokens());
        e.setDurationMs(c.durationMs());
        return e;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Map<String, Object> listRuns(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<EvalRunEntity> result = runRepository.findAllByOrderByIdDesc(pageable);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", result.getContent().stream().map(EvalService::toRunRow).toList());
        resp.put("page", result.getNumber());
        resp.put("size", result.getSize());
        resp.put("total", result.getTotalElements());
        resp.put("totalPages", result.getTotalPages());
        return resp;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String runId) {
        EvalRunEntity run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测运行不存在: " + runId));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("run", toRunRow(run));
        resp.put("cases", caseResultRepository.findByRunIdOrderByIdAsc(runId).stream()
                .map(EvalService::toCaseRow).toList());
        return resp;
    }

    /** 看板概览：最新一次运行 + 趋势 + 候选用例数 */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<EvalRunEntity> recent = runRepository.findTop30ByOrderByIdDesc();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("latest", recent.isEmpty() ? null : toRunRow(recent.get(0)));
        // 趋势按时间正序，便于前端画折线
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            EvalRunEntity r = recent.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("runId", r.getRunId());
            point.put("label", r.getLabel());
            point.put("passRate", r.getPassRate());
            point.put("toolAccuracy", r.getToolAccuracy());
            point.put("citationAccuracy", r.getCitationAccuracy());
            point.put("pointCoverage", r.getPointCoverage());
            point.put("refusalRate", r.getRefusalRate());
            point.put("createdAt", r.getCreatedAt());
            trend.add(point);
        }
        resp.put("trend", trend);
        resp.put("runCount", recent.size());
        resp.put("candidateNew", candidateRepository.countByStatus(EvalCandidateEntity.STATUS_NEW));
        return resp;
    }

    /** 两次运行对比（本次 vs 基线）：用例级修复/回归 + 指标 delta */
    @Transactional(readOnly = true)
    public Map<String, Object> diff(String runId, String baseRunId) {
        EvalRunEntity current = runRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测运行不存在: " + runId));
        EvalRunEntity base = runRepository.findByRunId(baseRunId)
                .orElseThrow(() -> new IllegalArgumentException("基线运行不存在: " + baseRunId));
        List<EvalCaseResultEntity> baseCases = caseResultRepository.findByRunIdOrderByIdAsc(baseRunId);
        List<EvalCaseResultEntity> currentCases = caseResultRepository.findByRunIdOrderByIdAsc(runId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("baseRunId", base.getRunId());
        resp.put("currentRunId", current.getRunId());
        resp.put("base", toRunRow(base));
        resp.put("current", toRunRow(current));
        resp.put("fixedCases", fixedCases(baseCases, currentCases));
        resp.put("brokenCases", brokenCases(baseCases, currentCases));
        resp.put("metricDeltas", metricDeltas(base, current));
        return resp;
    }

    // ==================== 纯函数（可单测） ====================

    /** 由失败转通过的用例 id（按用例 id 关联两次运行） */
    static List<String> fixedCases(List<EvalCaseResultEntity> base, List<EvalCaseResultEntity> current) {
        Map<String, Boolean> before = passMap(base);
        List<String> out = new ArrayList<>();
        for (EvalCaseResultEntity c : current) {
            if (Boolean.TRUE.equals(c.getPassed()) && Boolean.FALSE.equals(before.get(c.getCaseId()))) {
                out.add(c.getCaseId());
            }
        }
        return out;
    }

    /** 由通过转失败的用例 id（回归信号） */
    static List<String> brokenCases(List<EvalCaseResultEntity> base, List<EvalCaseResultEntity> current) {
        Map<String, Boolean> before = passMap(base);
        List<String> out = new ArrayList<>();
        for (EvalCaseResultEntity c : current) {
            if (Boolean.FALSE.equals(c.getPassed()) && Boolean.TRUE.equals(before.get(c.getCaseId()))) {
                out.add(c.getCaseId());
            }
        }
        return out;
    }

    static Map<String, Double> metricDeltas(EvalRunEntity base, EvalRunEntity current) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("passRate", delta(base.getPassRate(), current.getPassRate()));
        deltas.put("toolAccuracy", delta(base.getToolAccuracy(), current.getToolAccuracy()));
        deltas.put("citationAccuracy", delta(base.getCitationAccuracy(), current.getCitationAccuracy()));
        deltas.put("pointCoverage", delta(base.getPointCoverage(), current.getPointCoverage()));
        deltas.put("refusalRate", delta(base.getRefusalRate(), current.getRefusalRate()));
        return deltas;
    }

    private static Map<String, Boolean> passMap(List<EvalCaseResultEntity> cases) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (EvalCaseResultEntity c : cases) {
            map.put(c.getCaseId(), Boolean.TRUE.equals(c.getPassed()));
        }
        return map;
    }

    static Double delta(Double base, Double current) {
        return (base == null || current == null) ? null : current - base;
    }

    static Map<String, Object> toRunRow(EvalRunEntity r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", r.getRunId());
        row.put("label", r.getLabel());
        row.put("datasetName", r.getDatasetName());
        row.put("mode", r.getMode());
        row.put("model", r.getModel());
        row.put("judgeEnabled", Boolean.TRUE.equals(r.getJudgeEnabled()));
        row.put("total", nz(r.getTotal()));
        row.put("passed", nz(r.getPassed()));
        row.put("failed", nz(r.getFailed()));
        row.put("passRate", r.getPassRate());
        row.put("toolAccuracy", r.getToolAccuracy());
        row.put("citationAccuracy", r.getCitationAccuracy());
        row.put("pointCoverage", r.getPointCoverage());
        row.put("refusalRate", r.getRefusalRate());
        row.put("avgTokens", r.getAvgTokens());
        row.put("avgDurationMs", r.getAvgDurationMs());
        row.put("generatedAt", r.getGeneratedAt());
        row.put("createdAt", r.getCreatedAt());
        return row;
    }

    static Map<String, Object> toCaseRow(EvalCaseResultEntity c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", c.getCaseId());
        row.put("category", c.getCategory());
        row.put("passed", Boolean.TRUE.equals(c.getPassed()));
        row.put("failedChecks", splitCsv(c.getFailedChecks()));
        row.put("checksJson", c.getChecksJson());
        row.put("answer", c.getAnswerExcerpt());
        row.put("actualTools", splitCsv(c.getActualTools()));
        row.put("citedDocs", splitCsv(c.getCitedDocs()));
        row.put("retrievedDocs", splitCsv(c.getRetrievedDocs()));
        row.put("tokens", nz(c.getTokens()));
        row.put("durationMs", c.getDurationMs() == null ? 0L : c.getDurationMs());
        return row;
    }

    // ==================== 小工具 ====================

    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private static String csv(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = String.join(",", values);
        return joined.length() <= limit ? joined : joined.substring(0, limit);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
