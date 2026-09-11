package com.example.backend.agent.trace;

import java.util.List;

/**
 * 单轮对话的全部 trace 数据：用量 + 工具调用 + RAG 检索。
 * agent 同步 JSON / SSE 事件汇总后产出；空实现用于 agent 未回传任何 trace 时兜底。
 */
public record TraceData(UsageData usage, List<ToolData> tools, List<RagData> recalls) {

    public static TraceData empty() {
        return new TraceData(UsageData.empty(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return (usage == null || usage.rounds() == 0) && tools.isEmpty() && recalls.isEmpty();
    }
}
