package com.example.backend.mq;

/**
 * 事件消费者：每个主题一个或多个处理器。
 *
 * 实现类必须幂等或可重入：MQ 语义是「至少一次」，重投会重复调用。
 */
public interface EventHandler<E extends DomainEvent> {

    /** 处理的事件类型（注册表据此反序列化） */
    Class<E> type();

    /** 订阅的主题（与 {@link E#topic()} 一致） */
    String topic();

    void handle(E event) throws Exception;
}
