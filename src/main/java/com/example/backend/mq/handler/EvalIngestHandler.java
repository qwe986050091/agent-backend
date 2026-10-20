package com.example.backend.mq.handler;

import com.example.backend.eval.EvalReportPayload;
import com.example.backend.eval.EvalService;
import com.example.backend.mq.EventHandler;
import com.example.backend.mq.EventTopics;
import com.example.backend.mq.event.EvalIngestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 评测任务投递消费者：把 agent 上报的评测报告异步落库。
 *
 * 幂等：{@link EvalService#ingest} 按 runId 覆盖（同 runId 先删旧逐例再写），重投安全。
 */
@Component
public class EvalIngestHandler implements EventHandler<EvalIngestEvent> {

    private final EvalService evalService;
    private final ObjectMapper objectMapper;

    public EvalIngestHandler(EvalService evalService, ObjectMapper objectMapper) {
        this.evalService = evalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Class<EvalIngestEvent> type() {
        return EvalIngestEvent.class;
    }

    @Override
    public String topic() {
        return EventTopics.EVAL_INGEST;
    }

    @Override
    public void handle(EvalIngestEvent event) throws Exception {
        if (event == null || event.payload() == null || event.payload().isBlank()) {
            return;
        }
        EvalReportPayload payload = objectMapper.readValue(event.payload(), EvalReportPayload.class);
        evalService.ingest(payload);
    }
}
