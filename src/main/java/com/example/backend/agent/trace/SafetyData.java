package com.example.backend.agent.trace;

import java.util.List;

/**
 * 一次内容安全检测（对应 agent 的 event: safety，P4-17）。
 * 回合内可多条（input/rag/tool_output/output 各通道），落 prompt_safety_record。
 */
public record SafetyData(String channel,
                         int score,
                         String verdict,
                         String detector,
                         String action,
                         String llmReason,
                         List<RuleHit> ruleHits,
                         String excerpt,
                         long latencyMs) {

    /** 命中规则明细（字段名与 agent SafetyTraceEvent.RuleHitView 对齐） */
    public record RuleHit(String ruleId, String category, int weight) {
    }
}