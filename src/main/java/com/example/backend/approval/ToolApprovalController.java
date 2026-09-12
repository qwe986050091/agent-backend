package com.example.backend.approval;

import com.example.backend.agent.AgentClient;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.Map;

/**
 * 高危工具审批决策接口（需登录；/api/chat/** 已在登录拦截器路径内）。
 *
 *   POST /api/chat/approvals/{approvalId}/decision
 *     body: {"approved": true, "remember": false}
 *
 * 决策中转给 agent（归属校验在 agent 侧：仅条目本人可决策）；
 * 批准且勾选「始终允许」时把工具名写入 Redis 会话授权集合。
 */
@RestController
@RequestMapping("/api/chat/approvals")
public class ToolApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalController.class);

    private final AgentClient agentClient;
    private final ToolGrantStore grantStore;

    public ToolApprovalController(AgentClient agentClient, ToolGrantStore grantStore) {
        this.agentClient = agentClient;
        this.grantStore = grantStore;
    }

    @PostMapping("/{approvalId}/decision")
    public ResponseEntity<?> decide(HttpServletRequest request,
                                    @PathVariable String approvalId,
                                    @RequestBody(required = false) Map<String, Object> body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        boolean approved = body != null && Boolean.TRUE.equals(body.get("approved"));
        boolean remember = body != null && Boolean.TRUE.equals(body.get("remember"));
        String userId = user.getUsername();

        Map<String, Object> agentResp;
        try {
            agentResp = agentClient.decideApproval(approvalId, userId, approved, remember);
        } catch (HttpClientErrorException e) {
            // agent 404（不存在/非本人）/409（已决策）原样透传
            log.warn("[Approval] agent 拒绝决策: id={}, status={}, body={}",
                    approvalId, e.getStatusCode(), e.getResponseBodyAsString());
            Map<String, Object> payload = parseErrorBody(e);
            return ResponseEntity.status(e.getStatusCode()).body(payload);
        } catch (Exception e) {
            log.warn("[Approval] 决策中转失败（agent 不可达）: id={}, err={}", approvalId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "AGENT_UNAVAILABLE",
                            "message", "AI 服务暂时不可用，审批未能提交，请稍后重试"));
        }

        // 批准 + 始终允许：按 agent 回执的 tool/conversationId 持久化会话授权
        if (approved && remember) {
            String tool = agentResp.get("tool") == null ? null : String.valueOf(agentResp.get("tool"));
            Long conversationId = toLong(agentResp.get("conversationId"));
            grantStore.grant(userId, conversationId, tool);
        }
        return ResponseEntity.ok(agentResp);
    }

    private Map<String, Object> parseErrorBody(HttpClientErrorException e) {
        try {
            Map<?, ?> parsed = e.getResponseBodyAs(Map.class);
            if (parsed != null) {
                Map<String, Object> out = new HashMap<>();
                parsed.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignore) {
            // 响应体非 JSON 时用默认错误
        }
        return Map.of("code", "APPROVAL_ERROR", "message", "审批决策失败：" + e.getStatusCode());
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
