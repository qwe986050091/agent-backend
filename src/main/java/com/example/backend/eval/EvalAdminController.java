package com.example.backend.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 评测管理后台（P0-B，管理员白名单）：
 *
 *   GET  /api/admin/eval/runs              运行列表（分页）
 *   GET  /api/admin/eval/runs/{runId}      运行详情（含逐例结果）
 *   GET  /api/admin/eval/runs/{runId}/diff 与指定基线运行对比（修复/回归/指标 delta）
 *   GET  /api/admin/eval/stats             看板概览（最新运行 + 指标趋势 + 候选数）
 *   GET  /api/admin/eval/candidates        反馈反哺的候选用例（默认 new）
 *   POST /api/admin/eval/candidates/refresh 扫描点踩反馈，生成候选用例
 *   POST /api/admin/eval/candidates/{id}/promote 采纳（返回可粘进数据集的用例 JSON 草稿）
 *   POST /api/admin/eval/candidates/{id}/dismiss 忽略
 *
 * 登录由 AuthInterceptor 保证，管理员身份由 AdminInterceptor 二次校验（403）。
 */
@RestController
@RequestMapping("/api/admin/eval")
public class EvalAdminController {

    private final EvalService evalService;
    private final EvalCandidateService candidateService;

    public EvalAdminController(EvalService evalService, EvalCandidateService candidateService) {
        this.evalService = evalService;
        this.candidateService = candidateService;
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return evalService.listRuns(page, size);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        return evalService.getRun(runId);
    }

    @GetMapping("/runs/{runId}/diff")
    public Map<String, Object> diff(@PathVariable String runId, @RequestParam String base) {
        return evalService.diff(runId, base);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return evalService.stats();
    }

    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam(required = false) String status) {
        List<Map<String, Object>> rows = candidateService.list(status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", rows);
        resp.put("total", rows.size());
        return resp;
    }

    @PostMapping("/candidates/refresh")
    public Map<String, Object> refreshCandidates() {
        int created = candidateService.refresh();
        return Map.of("created", created);
    }

    @PostMapping("/candidates/{id}/promote")
    public Map<String, Object> promote(@PathVariable Long id) {
        return candidateService.promote(id);
    }

    @PostMapping("/candidates/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable Long id) {
        return candidateService.dismiss(id);
    }
}
