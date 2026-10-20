package com.example.backend.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 5.x 实现的消息总线（P1-E 生产路径）。
 *
 * 生产端：同步发送 + 3s 超时（失败由 {@link EventPublisher} 回退本地分发）。
 * 消费端：PushConsumer 订阅注册表里的全部主题，处理成功 ACK、异常返回 RECONSUME_LATER（最多 16 次后进死信）。
 *
 * 部署形态：中间件独立部署在 JD 备机，新机经 SSH 隧道访问 127.0.0.1:9876（不开放公网端口）；
 * broker 侧 brokerIP1=127.0.0.1，让客户端沿同一条隧道回到 broker。
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "type", havingValue = "rocketmq")
public class RocketMqEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RocketMqEventBus.class);

    private final EventHandlerRegistry registry;
    private final String namesrv;
    private final String topicPrefix;
    private final String group;

    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;

    public RocketMqEventBus(EventHandlerRegistry registry,
                            @Value("${app.mq.rocketmq.namesrv:127.0.0.1:9876}") String namesrv,
                            @Value("${app.mq.topic-prefix:app-}") String topicPrefix,
                            @Value("${app.mq.consumer-group:agent-backend}") String group) {
        this.registry = registry;
        this.namesrv = namesrv;
        this.topicPrefix = topicPrefix;
        this.group = group;
    }

    @Override
    public String name() {
        return "rocketmq";
    }

    @PostConstruct
    void start() throws Exception {
        producer = new DefaultMQProducer(group + "-producer");
        producer.setNamesrvAddr(namesrv);
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendFailed(1);
        producer.start();

        if (!registry.isEmpty()) {
            consumer = new DefaultMQPushConsumer(group + "-consumer");
            consumer.setNamesrvAddr(namesrv);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            for (String topic : registry.topics()) {
                consumer.subscribe(topicName(topic), "*");
            }
            consumer.setMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var msg : messages) {
                    String topic = msg.getTopic().substring(topicPrefix.length());
                    String payload = new String(msg.getBody(), StandardCharsets.UTF_8);
                    boolean ok = registry.dispatch(topic, payload);
                    if (!ok) {
                        log.warn("[MQ] RocketMQ 消息处理失败，稍后重投: topic={} msgId={}",
                                topic, msg.getMsgId());
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
        }
        log.info("[MQ] RocketMQ 就绪：namesrv={}, topics={}, group={}",
                namesrv, registry.topics(), group);
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        Message message = new Message(topicName(topic), payload.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            message.setKeys(key);
        }
        SendResult result = producer.send(message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("发送状态异常: "
                    + (result == null ? "null" : result.getSendStatus()));
        }
    }

    private String topicName(String topic) {
        return topicPrefix + topic;
    }

    @PreDestroy
    void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }
}
