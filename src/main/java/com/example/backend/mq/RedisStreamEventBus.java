package com.example.backend.mq;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Streams 实现的轻量消息总线（P1-E）。
 *
 * 适用：不想额外部署 Kafka/RocketMQ 时，复用现有 Redis。
 * 语义：生产者 XADD（MAXLEN 截断）→ 消费组 XREADGROUP ">" → 处理成功 XACK。
 * 失败不 ACK，留在 Pending List；启动时用 XAUTOCLAIM 认领超过 60s 未确认的消息重试。
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "type", havingValue = "redis")
public class RedisStreamEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventBus.class);

    private final EventHandlerRegistry registry;
    private final StringRedisTemplate redis;
    private final String streamPrefix;
    private final String group;
    private final long maxLen;
    private final String consumer;

    public RedisStreamEventBus(EventHandlerRegistry registry,
                               StringRedisTemplate redis,
                               @Value("${app.mq.stream-prefix:app-event:}") String streamPrefix,
                               @Value("${app.mq.consumer-group:agent-backend}") String group,
                               @Value("${app.mq.max-len:10000}") long maxLen) {
        this.registry = registry;
        this.redis = redis;
        this.streamPrefix = streamPrefix;
        this.group = group;
        this.maxLen = Math.max(100, maxLen);
        this.consumer = consumerName();
        // 提前建消费组（XGROUP CREATE ... $ MKSTREAM）：否则首次 XREADGROUP ">" 会报 NOGROUP
        for (String topic : registry.topics()) {
            ensureGroup(stream(topic));
        }
        log.info("[MQ] Redis Streams 就绪：prefix={}, group={}, consumer={}", streamPrefix, group, consumer);
    }

    @Override
    public String name() {
        return "redis-stream";
    }

    @Override
    public void publish(String topic, String key, String payload) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payload", payload);
        if (key != null) {
            body.put("key", key);
        }
        redis.opsForStream().add(stream(topic), body);
        redis.opsForStream().trim(stream(topic), maxLen);
    }

    /** 定时消费所有已订阅主题（每批 16 条，处理完再 ACK） */
    @Scheduled(fixedDelayString = "${app.mq.poll-interval-ms:500}")
    public void poll() {
        if (registry.isEmpty()) {
            return;
        }
        for (String topic : registry.topics()) {
            String stream = stream(topic);
            try {
                ensureGroup(stream);
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(group, consumer),
                        StreamReadOptions.empty().count(16),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                handleRecords(topic, stream, records);
                claimStale(topic, stream);
            } catch (Exception e) {
                log.debug("[MQ] 拉取 {} 失败: {}", stream, e.getMessage());
            }
        }
    }

    private void handleRecords(String topic, String stream,
                               List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get("payload");
            boolean ok = payload != null && registry.dispatch(topic, payload.toString());
            if (ok) {
                ack(stream, record.getId().getValue());
            } else {
                log.warn("[MQ] {} 处理失败，保留待重试: {}", topic, record.getId().getValue());
            }
        }
    }

    /** 认领超过 60s 未 ACK 的消息（消费端崩溃/异常退出后的补偿） */
    private void claimStale(String topic, String stream) {
        try {
            var summary = redis.opsForStream().pending(stream, group);
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return;
            }
            var pending = redis.opsForStream().pending(stream, group,
                    org.springframework.data.domain.Range.unbounded(), 16L);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            var ids = pending.stream()
                    .map(org.springframework.data.redis.connection.stream.PendingMessage::getId)
                    .toArray(org.springframework.data.redis.connection.stream.RecordId[]::new);
            var claimed = redis.opsForStream().claim(stream, group, consumer,
                    java.time.Duration.ofSeconds(60), ids);
            handleRecords(topic, stream, claimed);
        } catch (Exception e) {
            log.debug("[MQ] 认领超时未确认消息失败（可忽略）: {}", e.getMessage());
        }
    }

    private void ack(String stream, String recordId) {
        try {
            redis.opsForStream().acknowledge(stream, group, recordId);
        } catch (Exception e) {
            log.debug("[MQ] ACK 失败 {} {}: {}", stream, recordId, e.getMessage());
        }
    }

    private void ensureGroup(String stream) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
        } catch (Exception e) {
            // BUSYGROUP：已存在，正常
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains("BUSYGROUP")) {
                log.debug("[MQ] 创建消费组 {} 失败（可忽略）: {}", stream, msg);
            }
        }
    }

    private String stream(String topic) {
        return streamPrefix + topic;
    }

    /** 实例名：主机名 + 进程 id（同一消费组内并发消费） */
    private static String consumerName() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "consumer-" + ProcessHandle.current().pid();
        }
    }

    @PreDestroy
    void shutdown() {
        // Redis Streams 无需显式关闭；未 ACK 的消息由 claimStale 补偿
    }
}
