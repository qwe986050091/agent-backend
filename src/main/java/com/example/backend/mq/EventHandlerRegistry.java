package com.example.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件处理器注册表：按主题索引 {@link EventHandler}，并把 JSON 反序列化后分发。
 *
 * 处理器由 Spring 注入（应用启动即确定），投递/消费两侧共用同一份映射，
 * 避免「生产者有主题、消费者没订阅」这类静默丢消息的问题。
 */
@Component
public class EventHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(EventHandlerRegistry.class);

    private final ObjectMapper objectMapper;
    private final Map<String, List<EventHandler<?>>> handlers = new LinkedHashMap<>();

    public EventHandlerRegistry(List<EventHandler<?>> beans, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (EventHandler<?> handler : beans) {
            handlers.computeIfAbsent(handler.topic(), k -> new java.util.ArrayList<>()).add(handler);
        }
        log.info("[MQ] 已注册事件处理器: {}",
                handlers.entrySet().stream()
                        .map(e -> e.getKey() + "×" + e.getValue().size())
                        .toList());
    }

    /** 所有已订阅主题（消费者据此订阅） */
    public java.util.Set<String> topics() {
        return handlers.keySet();
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * 分发一条 JSON 事件；单个处理器异常不影响其它处理器。
     *
     * @return 是否至少有一个处理器成功（false 时 MQ 侧应重投）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean dispatch(String topic, String payload) {
        List<EventHandler<?>> list = handlers.get(topic);
        if (list == null || list.isEmpty()) {
            log.debug("[MQ] 主题 {} 无处理器，丢弃消息", topic);
            return true;
        }
        boolean allOk = true;
        for (EventHandler handler : list) {
            try {
                DomainEvent event = (DomainEvent) objectMapper.readValue(payload, handler.type());
                handler.handle(event);
            } catch (Exception e) {
                allOk = false;
                log.warn("[MQ] 主题 {} 处理失败（{}）: {}", topic,
                        handler.getClass().getSimpleName(), e.getMessage());
            }
        }
        return allOk;
    }
}
