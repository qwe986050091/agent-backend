package com.example.backend.usage;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.observability.LlmUsageRecordRepository;
import com.example.backend.observability.LlmUsageRecordRepository.ConversationProjection;
import com.example.backend.observability.LlmUsageRecordRepository.ModelProjection;
import com.example.backend.observability.LlmUsageRecordRepository.TotalsProjection;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计（P2-10，仅查本人数据）：
 *   GET /api/usage/summary?from=yyyy-MM-dd&to=yyyy-MM-dd&conversationId=N
 * 返回 totals + 按模型/按会话分组。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int TOP_CONVERSATIONS = 50;

    private final LlmUsageRecordRepository usageRepository;

    public UsageController(LlmUsageRecordRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) Long conversationId) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String userId = user.getUsername();

        LocalDateTime fromTime = parseStart(from);
        LocalDateTime toTime = parseEnd(to);
        if (fromTime == null) {
            fromTime = LocalDateTime.now().minusDays(DEFAULT_RANGE_DAYS);
        }
        if (toTime == null) {
            toTime = LocalDateTime.now();
        }
        Long convFilter = conversationId;

        TotalsProjection totalsRow = usageRepository.totals(userId, fromTime, toTime, convFilter);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("turns", asLong(totalsRow.getTurns()));
        totals.put("inputTokens", asLong(totalsRow.getInputTokens()));
        totals.put("outputTokens", asLong(totalsRow.getOutputTokens()));
        totals.put("totalTokens", asLong(totalsRow.getTotalTokens()));
        totals.put("costYuan", asMoney(totalsRow.getCostYuan()));

        List<Map<String, Object>> byModel = new ArrayList<>();
        for (ModelProjection row : usageRepository.totalsByModel(userId, fromTime, toTime, convFilter)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", row.getModel() == null ? "unknown" : row.getModel());
            item.put("turns", asLong(row.getTurns()));
            item.put("inputTokens", asLong(row.getInputTokens()));
            item.put("outputTokens", asLong(row.getOutputTokens()));
            item.put("totalTokens", asLong(row.getTotalTokens()));
            item.put("costYuan", asMoney(row.getCostYuan()));
            byModel.add(item);
        }

        List<Map<String, Object>> byConversation = new ArrayList<>();
        for (ConversationProjection row : usageRepository.totalsByConversation(userId, fromTime, toTime,
                convFilter, PageRequest.of(0, TOP_CONVERSATIONS))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("conversationId", asLong(row.getConversationId()));
            item.put("title", row.getTitle());
            item.put("turns", asLong(row.getTurns()));
            item.put("totalTokens", asLong(row.getTotalTokens()));
            item.put("costYuan", asMoney(row.getCostYuan()));
            byConversation.add(item);
        }

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", fromTime.toString());
        range.put("to", toTime.toString());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("range", range);
        resp.put("totals", totals);
        resp.put("byModel", byModel);
        resp.put("byConversation", byConversation);
        return resp;
    }

    /** 起始时间：支持 yyyy-MM-dd 或 ISO 日期时间；空/非法返回 null 走默认值 */
    private static LocalDateTime parseStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ignore) {
            // 下面尝试按日期解析
        }
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 结束时间：日期粒度按「当天结束」处理为次日 00:00（半开区间） */
    private static LocalDateTime parseEnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ignore) {
            // 下面尝试按日期解析
        }
        try {
            return LocalDate.parse(raw.trim()).plusDays(1).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal asMoney(Object v) {
        return v instanceof BigDecimal b ? b : (v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO);
    }
}
