package com.example.backend.agent.trace;

/**
 * 一个 RAG 召回片段（对应 agent RagSegment）。
 *
 * @param channel     命中通道 vector / keyword / both（P1-9.3）
 * @param rerankScore Rerank 精排 raw logit（未启用精排时为 null）
 */
public record RagSegmentData(String docId,
                             String source,
                             String type,
                             String text,
                             double score,
                             String channel,
                             Double rerankScore) {
}
