package com.example.backend.mq.event;

import com.example.backend.mq.DomainEvent;
import com.example.backend.mq.EventTopics;

/**
 * 对话索引事件：一条已落库的对话消息，异步写入 agent 侧向量库（RAG 长期记忆）。
 *
 * 改造前用进程内线程池，进程重启会丢任务；现在走 MQ，可重试、可积压。
 */
public record ConversationIndexEvent(String userId, String role, String content) implements DomainEvent {

    @Override
    public String topic() {
        return EventTopics.CONVERSATION_INDEX;
    }
}
