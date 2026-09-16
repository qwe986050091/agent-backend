package com.example.backend.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * RAG 召回审计记录（P2-10）：每个召回片段一行。
 */
@Entity
@Table(name = "rag_recall_record")
public class RagRecallRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId = 0L;

    @Column(name = "query_text", length = 1000)
    private String queryText;

    @Column(name = "doc_id", length = 64)
    private String docId;

    @Column(name = "source_name", length = 255)
    private String sourceName;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "segment_text", columnDefinition = "TEXT")
    private String segmentText;

    @Column(nullable = false)
    private Double score = 0d;

    /** 命中通道：vector / keyword / both（混合检索，P1-9.3；历史行/miss 行为 null） */
    @Column(length = 10)
    private String channel;

    /** Rerank 精排 raw logit（P1-9.3；未启用精排时为 null） */
    @Column(name = "rerank_score")
    private Double rerankScore;

    @Column(nullable = false)
    private Integer seq = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSegmentText() { return segmentText; }
    public void setSegmentText(String segmentText) { this.segmentText = segmentText; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Double getRerankScore() { return rerankScore; }
    public void setRerankScore(Double rerankScore) { this.rerankScore = rerankScore; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
