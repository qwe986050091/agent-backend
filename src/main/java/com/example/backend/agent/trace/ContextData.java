package com.example.backend.agent.trace;

import java.util.List;

/**
 * 本轮上下文构成（对应 agent 的 event: context，P1-D，字段名两侧对齐）。
 *
 * @param strategy          裁剪策略：balanced | history-priority
 * @param totalBudgetTokens 四段合计预算
 * @param totalTokens       实际估算总用量
 * @param overBudget        是否超总预算
 * @param segments          四段明细（system / summary / retrieval / history）
 */
public record ContextData(String strategy,
                          int totalBudgetTokens,
                          int totalTokens,
                          boolean overBudget,
                          List<Segment> segments) {

    /**
     * 单段明细。
     *
     * @param key          段标识：system / summary / retrieval / history
     * @param label        中文展示名
     * @param budgetTokens 该段预算
     * @param tokens       该段实际估算用量
     * @param truncated    是否发生了裁剪
     * @param detail       补充说明（保留/丢弃条数等）
     */
    public record Segment(String key,
                          String label,
                          int budgetTokens,
                          int tokens,
                          boolean truncated,
                          String detail) {
    }
}
