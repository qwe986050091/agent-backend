package com.example.backend.agent.trace;

/**
 * 一次工具调用（对应 agent 的 event: tool）。
 *
 * agentId：发起该工具调用的 Agent 归属 —— null=主 Agent（顶层），非空=子代理技能名
 * （Agent-as-Tool 嵌套归属，用于把工具调用按「哪个 Agent 干的」分组复盘）。
 */
public record ToolData(int round,
                       String id,
                       String name,
                       String arguments,
                       String result,
                       boolean success,
                       String error,
                       long durationMs,
                       String agentId) {
}
