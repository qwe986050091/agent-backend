package com.example.backend.mq.event;

import com.example.backend.mq.DomainEvent;
import com.example.backend.mq.EventTopics;

/**
 * 站内通知事件：任务完成等业务事件异步生成通知（落库 + SSE 推送）。
 */
public record NotificationCreatedEvent(String userId,
                                       String type,
                                       String title,
                                       String body,
                                       Long targetConversationId) implements DomainEvent {

    @Override
    public String topic() {
        return EventTopics.NOTIFICATION;
    }
}
