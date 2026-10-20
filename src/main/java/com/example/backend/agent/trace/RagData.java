package com.example.backend.agent.trace;

import java.util.List;

/**
 * 一次 RAG 检索（对应 agent 的 event: rag）。
 *
 * agentId：发起检索的 Agent —— null=主 Agent，非空=子代理技能名。
 */
public record RagData(String query, List<RagSegmentData> segments, String agentId) {
}
