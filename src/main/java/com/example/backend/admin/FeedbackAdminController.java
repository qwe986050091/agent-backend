package com.example.backend.admin;

import com.example.backend.feedback.FeedbackService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bad case 后台（P2-11.1，管理员白名单）：
 *
 *   GET /api/admin/feedback        反馈分页检索（评分/原因/关键词/时间范围，富化问题与答案）
 *   GET /api/admin/feedback/stats  总数/赞/踩 + 点踩原因分布
 *
 * 登录由 AuthInterceptor 保证，管理员身份由 AdminInterceptor 二次校验（403）。
 */
@RestController
@RequestMapping("/api/admin/feedback")
public class FeedbackAdminController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final FeedbackService feedbackService;

    public FeedbackAdminController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Integer rating,
                                    @RequestParam(required = false) String reason,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        // rating 仅允许 1/-1；其它值（含 null）视为不过滤
        Integer ratingFilter = (rating != null && (rating == 1 || rating == -1)) ? rating : null;
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<Map<String, Object>> result = feedbackService.searchAdmin(
                ratingFilter, reason, keyword,
                parseStart(from), parseEnd(to),
                PageRequest.of(pageIndex, pageSize));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", result.getContent());
        resp.put("page", result.getNumber());
        resp.put("size", result.getSize());
        resp.put("total", result.getTotalElements());
        resp.put("totalPages", result.getTotalPages());
        return resp;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        Map<String, Object> resp = new LinkedHashMap<>(
                feedbackService.stats(parseStart(from), parseEnd(to)));
        return resp;
    }

    /** 起始时间：支持 yyyy-MM-dd 或 ISO 日期时间；空/非法返回 null（不过滤） */
    private static LocalDateTime parseStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignore) {
            // 尝试按日期解析
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 结束时间：日期粒度按次日 00:00（半开区间） */
    private static LocalDateTime parseEnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignore) {
            // 尝试按日期解析
        }
        try {
            return LocalDate.parse(text).plusDays(1).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
