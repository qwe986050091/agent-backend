package com.example.backend.agent.trace;

import java.util.List;

/**
 * 一次 RAG 检索（对应 agent 的 event: rag）。
 */
public record RagData(String query, List<RagSegmentData> segments) {
}
