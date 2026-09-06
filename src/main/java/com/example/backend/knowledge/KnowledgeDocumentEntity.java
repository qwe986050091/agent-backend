package com.example.backend.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * RAG 知识文档元数据。
 * 文档全文不落 MySQL：上传后切块向量化，存入 agent 侧内存向量库。
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上传用户（用户名） */
    @Column(name = "username", nullable = false, length = 100)
    private String username;

    /** 文档唯一标识（UUID，与向量库 metadata.docId 对应） */
    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    /** 原始文件名 */
    @Column(name = "doc_name", nullable = false)
    private String docName;

    /** 文件类型（txt/md/text） */
    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    /** 切块（向量片段）数量 */
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

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
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getDocName() { return docName; }
    public void setDocName(String docName) { this.docName = docName; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}