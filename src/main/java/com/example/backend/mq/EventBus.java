package com.example.backend.mq;

/**
 * 消息总线抽象（P1-E）。
 *
 * 三种实现：
 *   - {@code DirectEventBus}：不接中间件，异步线程内直接分发（默认，行为等价于改造前）；
 *   - {@code RedisStreamEventBus}：Redis Streams，复用现有 Redis，零额外进程；
 *   - {@code RocketMqEventBus}：RocketMQ 5.x（生产，NameServer + Broker，消费组重试）。
 *
 * 选择方式：{@code app.mq.type=none|redis|rocketmq}。
 */
public interface EventBus {

    /** 实现名（日志/健康检查用） */
    String name();

    /**
     * 投递一条事件。
     *
     * @param topic   主题（不含前缀）
     * @param key     业务键（用于分区/去重定位；可为 null）
     * @param payload JSON 字符串
     * @throws Exception 投递失败（上层会回退为本地分发，保证事件不丢）
     */
    void publish(String topic, String key, String payload) throws Exception;
}
