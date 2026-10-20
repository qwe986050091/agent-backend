package com.example.backend.mq;

/**
 * 领域事件（异步链路的消息体）。
 *
 * 实现类必须是 Jackson 可序列化的 record；topic 决定走哪条队列/流。
 */
public interface DomainEvent {

    /** 事件主题（不加前缀，由 EventBus 统一拼接） */
    String topic();
}
