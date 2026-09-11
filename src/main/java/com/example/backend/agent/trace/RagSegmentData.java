package com.example.backend.agent.trace;

/**
 * 一个 RAG 召回片段（对应 agent RagSegment）。
 */
public record RagSegmentData(String docId,
                             String source,
                             String type,
                             String text,
                             double score) {
}
