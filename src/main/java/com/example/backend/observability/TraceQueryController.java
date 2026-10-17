package com.example.backend.observability;

import com.example.backend.agent.trace.RagData;
import com.example.backend.agent.trace.RagSegmentData;
import com.example.backend.agent.trace.ToolData;
import com.example.backend.agent.trace.UsageData;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.chat.ChatMessageRepository;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史 trace 回查（P2-10）：GET /api/chat/traces/{traceId}
 * 先以 traceId + 当前用户校验归属（chat_message），再组装与实时 SSE 事件同构的数据。
 */
@RestController
@RequestMapping("/api/chat/traces")
public class TraceQueryController {

    private final ChatMessageRepository chatMessageRepository;
    private final TracePersistenceService tracePersistenceService;

    public TraceQueryController(ChatMessageRepository chatMessageRepository,
                                TracePersistenceService tracePersistenceService) {
        this.chatMessageRepository = chatMessageRepository;
        this.tracePersistenceService = tracePersistenceService;
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<?> getTrace(HttpServletRequest request,
                                      @PathVariable String traceId) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String userId = user.getUsername();

        // 归属校验：traceId 必须存在于该用户的某条消息上
        if (traceId == null || traceId.isBlank()
                || !chatMessageRepository.existsByTraceIdAndUserId(traceId, userId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "TRACE_NOT_FOUND", "message", "trace 不存在或无权查看"));
        }

        // usage 用 Map 输出，附落库时计算的 costYuan（实时事件同构）
        Map<String, Object> usage = tracePersistenceService.findUsage(traceId)
                .map(TraceQueryController::toUsageMap)
                .orElseGet(() -> objectToMap(UsageData.empty()));

        List<ToolData> tools = new ArrayList<>();
        for (ToolCallRecordEntity t : tracePersistenceService.findTools(traceId)) {
            tools.add(new ToolData(t.getRoundSeq(), null, t.getToolName(), t.getArguments(),
                    t.getResultPreview(), Boolean.TRUE.equals(t.getSuccess()),
                    t.getErrorMessage(), t.getDurationMs() == null ? 0L : t.getDurationMs(),
                    t.getAgentId()));
        }

        // 片段行按 query 分组还原 RagData（保持落库顺序）；miss 行（无片段字段）产生空 segments 组
        Map<String, List<RagSegmentData>> grouped = new LinkedHashMap<>();
        for (RagRecallRecordEntity r : tracePersistenceService.findRecalls(traceId)) {
            String query = r.getQueryText() == null ? "" : r.getQueryText();
            List<RagSegmentData> segments = grouped.computeIfAbsent(query, k -> new ArrayList<>());
            boolean missRow = r.getSegmentText() == null && r.getDocId() == null
                    && r.getSourceName() == null;
            if (!missRow) {
                segments.add(new RagSegmentData(
                        r.getDocId(), r.getSourceName(), r.getSourceType(), r.getSegmentText(),
                        r.getScore() == null ? 0d : r.getScore(),
                        r.getChannel(), r.getRerankScore()));
            }
        }
        List<RagData> recalls = new ArrayList<>();
        grouped.forEach((query, segments) -> recalls.add(new RagData(query, segments)));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("traceId", traceId);
        resp.put("usage", usage);
        resp.put("tools", tools);
        resp.put("recalls", recalls);
        return ResponseEntity.ok(resp);
    }

    private static Map<String, Object> toUsageMap(LlmUsageRecordEntity u) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", u.getModel());
        map.put("inputTokens", nz(u.getInputTokens()));
        map.put("outputTokens", nz(u.getOutputTokens()));
        map.put("totalTokens", nz(u.getTotalTokens()));
        map.put("rounds", nz(u.getRounds()));
        map.put("fallback", Boolean.TRUE.equals(u.getFallback()));
        map.put("cancelled", Boolean.TRUE.equals(u.getCancelled()));
        map.put("durationMs", u.getDurationMs() == null ? 0L : u.getDurationMs());
        map.put("costYuan", u.getCostYuan());
        return map;
    }

    private static Map<String, Object> objectToMap(UsageData usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", usage.model());
        map.put("inputTokens", usage.inputTokens());
        map.put("outputTokens", usage.outputTokens());
        map.put("totalTokens", usage.totalTokens());
        map.put("rounds", usage.rounds());
        map.put("fallback", usage.fallback());
        map.put("cancelled", usage.cancelled());
        map.put("durationMs", usage.durationMs());
        map.put("costYuan", 0);
        return map;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
