package com.example.backend.agent.trace;

/**
 * 整轮 token 用量（对应 agent 的 event: usage，字段名两侧对齐）。
 */
public record UsageData(String model,
                        int inputTokens,
                        int outputTokens,
                        int totalTokens,
                        int rounds,
                        boolean fallback,
                        boolean cancelled,
                        long durationMs) {

    public static UsageData empty() {
        return new UsageData(null, 0, 0, 0, 0, false, false, 0L);
    }
}
