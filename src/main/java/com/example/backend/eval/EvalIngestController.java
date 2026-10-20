package com.example.backend.eval;

import com.example.backend.mq.EventPublisher;
import com.example.backend.mq.event.EvalIngestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测上报入口（P0-B）：agent 侧 eval CLI 把报告 POST 到此落库。
 *
 * <p>该路径不在 AuthInterceptor 的保护列表内（机器调用，无登录态），
 * 因此改用独立服务令牌 {@code X-Eval-Token} 鉴权（常量时间比较，缺失/错配一律 401）。
 * nginx 未对外暴露 /api/eval/**，外部网络不可达。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalIngestController {

    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 上报令牌（生产经环境变量 EVAL_INGEST_TOKEN 注入，须与 agent 侧 eval.persist-token 一致） */
    @Value("${app.eval.ingest-token:}")
    private String ingestToken;

    public EvalIngestController(EventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/runs")
    public ResponseEntity<?> ingest(@RequestHeader(value = "X-Eval-Token", required = false) String token,
                                    @RequestBody EvalReportPayload payload) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHORIZED", "message", "评测上报令牌无效"));
        }
        if (payload == null || payload.runId() == null || payload.runId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", "runId 不能为空"));
        }
        try {
            // P1-E：评测任务投递——校验通过后进队列，由消费者异步落库（大报告不阻塞上报方）
            String json = objectMapper.writeValueAsString(payload);
            boolean queued = eventPublisher.publish(
                    new EvalIngestEvent(payload.runId(), json), payload.runId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", payload.runId());
            body.put("status", queued ? "queued" : "failed");
            return ResponseEntity.status(queued ? HttpStatus.ACCEPTED : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "INGEST_FAILED", "message", "报告投递失败: " + e.getMessage()));
        }
    }

    /** fail-closed：未配置或令牌不匹配一律拒绝；常量时间比较防时序侧信道 */
    private boolean authorized(String token) {
        if (ingestToken == null || ingestToken.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(ingestToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
