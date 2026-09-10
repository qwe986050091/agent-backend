package com.example.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, AgentMemoryId> {
}
