package com.example.backend.chat;

import java.io.Serializable;
import java.util.Objects;

/**
 * AgentMemoryEntity 复合主键：(user_id, conversation_id)
 */
public class AgentMemoryId implements Serializable {

    private String userId;
    private Long conversationId;

    public AgentMemoryId() {}

    public AgentMemoryId(String userId, Long conversationId) {
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentMemoryId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, conversationId);
    }
}
