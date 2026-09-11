package com.example.backend.agent.trace;

/**
 * 一次工具调用（对应 agent 的 event: tool）。
 */
public record ToolData(int round,
                       String id,
                       String name,
                       String arguments,
                       String result,
                       boolean success,
                       String error,
                       long durationMs) {
}
