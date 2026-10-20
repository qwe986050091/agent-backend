package com.example.backend.mq.event;

import com.example.backend.mq.DomainEvent;
import com.example.backend.mq.EventTopics;

/**
 * 评测任务投递事件：agent 侧评测报告已通过令牌校验，交给消费者异步落库。
 *
 * payload 是原始报告 JSON（保留 agent 侧字段契约，避免二次结构转换）。
 */
public record EvalIngestEvent(String runId, String payload) implements DomainEvent {

    @Override
    public String topic() {
        return EventTopics.EVAL_INGEST;
    }
}
