package com.example.backend.mq;

import com.example.backend.mq.event.ConversationIndexEvent;
import com.example.backend.mq.event.NotificationCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息总线核心语义单测（P1-E）：
 *   1) 注册表按 topic 反序列化并分发；
 *   2) 处理器抛异常时返回 false（MQ 侧据此重投）；
 *   3) 投递失败时 EventPublisher 回退本地分发，事件不丢。
 */
class EventPipelineTest {

    /** 记录型处理器 */
    static class RecordingHandler implements EventHandler<ConversationIndexEvent> {
        final List<ConversationIndexEvent> received = new ArrayList<>();
        final boolean fail;

        RecordingHandler(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Class<ConversationIndexEvent> type() {
            return ConversationIndexEvent.class;
        }

        @Override
        public String topic() {
            return EventTopics.CONVERSATION_INDEX;
        }

        @Override
        public void handle(ConversationIndexEvent event) {
            received.add(event);
            if (fail) {
                throw new IllegalStateException("模拟下游失败");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dispatchDeserializesAndRoutesByTopic() {
        RecordingHandler handler = new RecordingHandler(false);
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(handler), MAPPER);
        String json = "{\"userId\":\"u1\",\"role\":\"user\",\"content\":\"你好\"}";

        assertThat(registry.dispatch(EventTopics.CONVERSATION_INDEX, json)).isTrue();

        assertThat(handler.received).hasSize(1);
        assertThat(handler.received.get(0).userId()).isEqualTo("u1");
        assertThat(handler.received.get(0).content()).isEqualTo("你好");
        assertThat(registry.topics()).containsExactly(EventTopics.CONVERSATION_INDEX);
    }

    @Test
    void failedHandlerSignalsRetry() {
        RecordingHandler handler = new RecordingHandler(true);
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(handler), MAPPER);

        boolean ok = registry.dispatch(EventTopics.CONVERSATION_INDEX,
                "{\"userId\":\"u1\",\"role\":\"user\",\"content\":\"x\"}");

        assertThat(ok).isFalse();
        assertThat(handler.received).hasSize(1);
    }

    @Test
    void unknownTopicIsDroppedWithoutError() {
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(), MAPPER);

        assertThat(registry.dispatch("no-such-topic", "{}")).isTrue();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void publishFallsBackToLocalDispatchWhenBusFails() {
        RecordingHandler handler = new RecordingHandler(false);
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(handler), MAPPER);
        EventBus broken = new EventBus() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void publish(String topic, String key, String payload) {
                throw new IllegalStateException("broker down");
            }
        };
        EventPublisher publisher = new EventPublisher(broken, registry, MAPPER);

        boolean ok = publisher.publish(
                new ConversationIndexEvent("u2", "assistant", "回答"), "u2");

        assertThat(ok).isTrue();
        assertThat(handler.received).hasSize(1);
        assertThat(handler.received.get(0).userId()).isEqualTo("u2");
    }

    @Test
    void notificationEventRoundTripsThroughRegistry() {
        List<NotificationCreatedEvent> received = new ArrayList<>();
        EventHandler<NotificationCreatedEvent> handler = new EventHandler<>() {
            @Override
            public Class<NotificationCreatedEvent> type() {
                return NotificationCreatedEvent.class;
            }

            @Override
            public String topic() {
                return EventTopics.NOTIFICATION;
            }

            @Override
            public void handle(NotificationCreatedEvent event) {
                received.add(event);
            }
        };
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(handler), MAPPER);
        String json = "{\"userId\":\"u3\",\"type\":\"task_result\",\"title\":\"任务完成\","
                + "\"body\":\"结果\",\"targetConversationId\":9}";

        assertThat(registry.dispatch(EventTopics.NOTIFICATION, json)).isTrue();

        assertThat(received).hasSize(1);
        assertThat(received.get(0).targetConversationId()).isEqualTo(9L);
        assertThat(received.get(0).title()).isEqualTo("任务完成");
    }
}
