package com.example.backend.eval;

import java.util.List;

/**
 * 评测端（agent 仓 eval CLI）上报的报告载荷（P0-B）。
 * 字段名与 agent 侧 {@code EvalReport} 完全对齐，Jackson 直接反序列化 records。
 */
public record EvalReportPayload(String runId,
                                String label,
                                String datasetName,
                                String mode,
                                String model,
                                String generatedAt,
                                boolean judgeEnabled,
                                Metrics metrics,
                                List<CaseResult> results) {

    /** 聚合指标（toolAccuracy 等无样本时为 null，对应 agent 侧 N/A） */
    public record Metrics(int total,
                          int passed,
                          int failed,
                          double passRate,
                          Double toolAccuracy,
                          int toolChecked,
                          Double citationAccuracy,
                          int citationChecked,
                          Double pointCoverage,
                          int pointChecked,
                          Double refusalRate,
                          int refusalChecked,
                          double avgTokens,
                          double avgDurationMs) {
    }

    /** 单例结果 */
    public record CaseResult(String id,
                             String category,
                             String input,
                             boolean passed,
                             List<Check> checks,
                             String answer,
                             List<String> actualTools,
                             List<String> citedDocs,
                             List<String> retrievedDocs,
                             int tokens,
                             long durationMs) {
    }

    /** 单条断言 */
    public record Check(String name,
                        boolean passed,
                        String expected,
                        String actual,
                        String detail) {
    }
}
