package com.example.backend.feedback;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回答反馈接口（P2-11.1，需登录）：
 *
 *   POST /api/feedback  {traceId, rating, reasonCode?, comment?}
 *
 * rating：1=赞，-1=踩，0=取消；重复点同一评价也按取消处理。traceId 必须是本人回合，否则 404。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ResponseEntity<?> submit(HttpServletRequest request, @RequestBody FeedbackRequest body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (body == null || body.traceId() == null || body.traceId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", "traceId 不能为空"));
        }
        int effective = feedbackService.submit(
                user.getUsername(), body.traceId(), body.rating(), body.reasonCode(), body.comment());
        return ResponseEntity.ok(Map.of("traceId", body.traceId(), "rating", effective));
    }

    /** 点踩原因码字典（前端弹窗/后台筛选共用同一来源，保持前后端一致） */
    @GetMapping("/reasons")
    public ResponseEntity<?> reasons() {
        List<Map<String, String>> reasons = new ArrayList<>();
        FeedbackReasons.REASONS.forEach((code, label) ->
                reasons.add(Map.of("code", code, "label", label)));
        return ResponseEntity.ok(Map.of("reasons", reasons));
    }

    /** 提交反馈入参 */
    public record FeedbackRequest(String traceId, Integer rating, String reasonCode, String comment) {
    }
}
