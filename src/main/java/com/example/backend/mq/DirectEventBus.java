package com.example.backend.mq;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 默认总线：不接中间件，用有界线程池在进程内分发（等价于改造前的异步线程）。
 *
 * 用于本地开发与 MQ 故障时的降级；进程重启会丢在途事件，生产应切 redis/rocketmq。
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "type", havingValue = "none", matchIfMissing = true)
public class DirectEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(DirectEventBus.class);

    private final EventHandlerRegistry registry;
    private final ThreadPoolExecutor executor;

    public DirectEventBus(EventHandlerRegistry registry) {
        this.registry = registry;
        this.executor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "app-event-direct");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[MQ] 未启用中间件（app.mq.type=none），事件走进程内线程池");
    }

    @Override
    public String name() {
        return "direct";
    }

    @Override
    public void publish(String topic, String key, String payload) {
        executor.execute(() -> registry.dispatch(topic, payload));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
