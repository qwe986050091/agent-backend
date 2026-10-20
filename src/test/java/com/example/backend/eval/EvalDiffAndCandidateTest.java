package com.example.backend.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测看板的纯函数单测（CI 离线档）：运行对比（修复/回归/指标 delta）与反馈反哺启发式。
 */
class EvalDiffAndCandidateTest {

    // ==================== 运行对比 ====================

    @Test
    void fixedAndBrokenCasesAreComputedById() {
        List<EvalCaseResultEntity> base = List.of(
                caseResult("c1", true), caseResult("c2", false), caseResult("c3", true));
        List<EvalCaseResultEntity> current = List.of(
                caseResult("c1", false), caseResult("c2", true), caseResult("c3", true));

        assertThat(EvalService.fixedCases(base, current)).containsExactly("c2");
        assertThat(EvalService.brokenCases(base, current)).containsExactly("c1");
    }

    @Test
    void metricDeltasAreNullSafe() {
        EvalRunEntity base = run(0.5, 1.0, null, 0.4, 1.0);
        EvalRunEntity current = run(0.7, 1.0, 0.9, null, 0.8);

        var deltas = EvalService.metricDeltas(base, current);

        assertThat(deltas.get("passRate")).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(deltas.get("toolAccuracy")).isEqualTo(0.0);
        // base=null → delta 为 null（样本缺失，不误报）
        assertThat(deltas.get("citationAccuracy")).isNull();
        assertThat(deltas.get("pointCoverage")).isNull();
        assertThat(deltas.get("refusalRate")).isCloseTo(-0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void nullSafeDeltaHelper() {
        assertThat(EvalService.delta(null, 1.0)).isNull();
        assertThat(EvalService.delta(1.0, null)).isNull();
        assertThat(EvalService.delta(0.4, 0.6))
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ==================== 反馈反哺启发式 ====================

    @Test
    void suggestedCategoryPrefersRagForFactualOrKbQuestions() {
        assertThat(EvalCandidateService.suggestCategory("factual_error", "随便问")).isEqualTo("rag_qa");
        assertThat(EvalCandidateService.suggestCategory("other", "你们的知识库政策怎么写的")).isEqualTo("rag_qa");
        assertThat(EvalCandidateService.suggestCategory("incomplete", "你好")).isEqualTo("plain");
    }

    @Test
    void suggestedPointsExtractsShortSegmentsFromComment() {
        assertThat(EvalCandidateService.suggestPoints("没有说清楚退款要几天，也没提到原路退回，太简略"))
                .isEqualTo("没有说清楚退款要几天,也没提到原路退回,太简略");
        assertThat(EvalCandidateService.suggestPoints(null)).isNull();
        assertThat(EvalCandidateService.suggestPoints("  ")).isNull();
    }

    // ==================== 夹具 ====================

    private static EvalCaseResultEntity caseResult(String caseId, boolean passed) {
        EvalCaseResultEntity e = new EvalCaseResultEntity();
        e.setRunId("r");
        e.setCaseId(caseId);
        e.setPassed(passed);
        return e;
    }

    private static EvalRunEntity run(double passRate, Double tool, Double citation,
                                     Double point, Double refusal) {
        EvalRunEntity r = new EvalRunEntity();
        r.setRunId("r");
        r.setPassRate(passRate);
        r.setToolAccuracy(tool);
        r.setCitationAccuracy(citation);
        r.setPointCoverage(point);
        r.setRefusalRate(refusal);
        return r;
    }
}
