package com.example.backend.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

    List<KnowledgeDocumentEntity> findByUsernameOrderByIdDesc(String username);
}