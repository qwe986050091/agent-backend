package com.example.backend.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测上报载荷解析（CI 离线档）：固化「agent 侧 EvalReport JSON ↔ backend payload」的字段契约。
 * 任一侧字段改名会在此暴露（避免静默丢数据）。
 */
class EvalPayloadParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 与 agent 侧 EvalReportWriter 产出的 JSON 结构一致（节选一次真实运行） */
    private static final String REPORT_JSON = """
            {
              "runId": "20261020-153000-ab12",
              "label": "rerank-on",
              "datasetName": "agent-eval-v1",
              "mode": "in-process",
              "model": "deepseek-v4-flash",
              "generatedAt": "2026-10-20T15:30:00.123",
              "judgeEnabled": true,
              "metrics": {
                "total": 3, "passed": 2, "failed": 1, "passRate": 0.6666666666666666,
                "toolAccuracy": 1.0, "toolChecked": 2,
                "citationAccuracy": null, "citationChecked": 0,
                "pointCoverage": 0.5, "pointChecked": 2,
                "refusalRate": 1.0, "refusalChecked": 1,
                "avgTokens": 120.5, "avgDurationMs": 900.0
              },
              "results": [
                {
                  "id": "tool-calc-add", "category": "tool_call", "input": "128 加 375",
                  "passed": true,
                  "checks": [{"name":"tools","passed":true,"expected":"add","actual":"add","detail":""}],
                  "answer": "503", "actualTools": ["add"], "citedDocs": [],
                  "retrievedDocs": [], "tokens": 130, "durationMs": 800
                },
                {
                  "id": "rag-refund", "category": "rag_qa", "input": "退款政策",
                  "passed": false,
                  "checks": [{"name":"citations","passed":false,"expected":"doc-refund",
                              "actual":"（无引用）","detail":"答案没有任何引用标注 [n]"}],
                  "answer": "见文档", "actualTools": [], "citedDocs": [],
                  "retrievedDocs": ["doc-refund"], "tokens": 100, "durationMs": 1000
                }
              ]
            }
            """;

    @Test
    void payloadFieldsMapCorrectly() throws Exception {
        EvalReportPayload p = mapper.readValue(REPORT_JSON, EvalReportPayload.class);

        assertThat(p.runId()).isEqualTo("20261020-153000-ab12");
        assertThat(p.label()).isEqualTo("rerank-on");
        assertThat(p.judgeEnabled()).isTrue();
        assertThat(p.metrics().total()).isEqualTo(3);
        assertThat(p.metrics().toolAccuracy()).isEqualTo(1.0);
        assertThat(p.metrics().citationAccuracy()).isNull();  // 无样本 → null（N/A）
        assertThat(p.results()).hasSize(2);
        assertThat(p.results().get(0).checks().get(0).name()).isEqualTo("tools");
        assertThat(p.results().get(1).checks().get(0).passed()).isFalse();
        assertThat(p.results().get(1).retrievedDocs()).containsExactly("doc-refund");
    }
}
