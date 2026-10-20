package com.example.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件发布入口（唯一写点）。
 *
 * 语义：**先投递、失败就地兜底**。中间件不可用时不能让业务链路报错，
 * 也不能静默丢事件——因此投递异常会回退为本地同步分发（与改造前行为一致），
 * 并打 WARN 便于告警。
 */
@Service
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final EventBus bus;
    private final EventHandlerRegistry registry;
    private final ObjectMapper objectMapper;

    public EventPublisher(EventBus bus, EventHandlerRegistry registry, ObjectMapper objectMapper) {
        this.bus = bus;
        this.registry = registry;
        this.objectMapper = objectMapper;
        log.info("[MQ] 事件总线实现: {}，订阅主题: {}", bus.name(), registry.topics());
    }

    /**
     * 投递一条事件；返回 true 表示已交给中间件（或就地分发成功）。
     */
    public boolean publish(DomainEvent event, String key) {
        if (event == null || event.topic() == null) {
            return false;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[MQ] 事件序列化失败（{}）: {}", event.topic(), e.getMessage());
            return false;
        }
        try {
            bus.publish(event.topic(), key, payload);
            return true;
        } catch (Exception e) {
            log.warn("[MQ] 投递失败（{}，{}），回退本地分发: {}", event.topic(), bus.name(), e.getMessage());
            return registry.dispatch(event.topic(), payload);
        }
    }

    /** 当前总线实现名（健康检查/日志） */
    public String busName() {
        return bus.name();
    }
}
