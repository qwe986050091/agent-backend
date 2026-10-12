package com.example.backend.task;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务管理接口（P2-9，需登录；归属校验按用户名）
 *
 *   POST   /api/tasks              创建（校验 cron/防呆参数；服务端算 next_run_at）
 *   GET    /api/tasks              列表（含 next_run_at 与上次执行结果）
 *   PATCH  /api/tasks/{id}         更新（改 cron/启停时重算 next_run_at）
 *   DELETE /api/tasks/{id}         删除（连带执行记录；专属会话保留）
 *   POST   /api/tasks/{id}/run-now 立即执行（不动 next_run_at；1 分钟/次 + 计入每日上限）
 *   GET    /api/tasks/{id}/runs    执行记录分页
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody(required = false) TaskRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "请求体为空"));
        }
        String userId = currentUser(request);
        ScheduledTaskEntity task = taskService.create(userId, body);
        return ResponseEntity.ok(toMap(task, null));
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        String userId = currentUser(request);
        List<Map<String, Object>> tasks = taskService.listOwned(userId).stream()
                .map(t -> toMap(t, taskService.lastRun(t.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("tasks", tasks));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody(required = false) TaskRequest body,
                                    HttpServletRequest request) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "请求体为空"));
        }
        ScheduledTaskEntity task = taskService.update(currentUser(request), id, body);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toMap(task, taskService.lastRun(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        boolean deleted = taskService.delete(currentUser(request), id);
        return deleted
                ? ResponseEntity.ok(Map.of("message", "已删除（任务专属会话与历史消息已保留）"))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<?> runNow(@PathVariable Long id, HttpServletRequest request) {
        TaskService.RunNowOutcome outcome = taskService.runNow(currentUser(request), id);
        if (outcome.code() == null) {
            return ResponseEntity.ok(Map.of("message", outcome.message()));
        }
        return ResponseEntity.status(outcome.httpStatus())
                .body(Map.of("code", outcome.code(), "message", outcome.message()));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> runs(@PathVariable Long id,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  HttpServletRequest request) {
        String userId = currentUser(request);
        if (taskService.findOwned(id, userId) == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> runs = taskService.runs(id, page, size).stream()
                .map(TaskController::runToMap)
                .toList();
        return ResponseEntity.ok(Map.of("runs", runs));
    }

    private static String currentUser(HttpServletRequest request) {
        return ((UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).getUsername();
    }

    private Map<String, Object> toMap(ScheduledTaskEntity task, ScheduledTaskRunEntity lastRun) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", task.getId());
        m.put("name", task.getName());
        m.put("prompt", task.getPrompt());
        m.put("cronExpr", task.getCronExpr());
        m.put("enabled", task.isEnabled());
        m.put("toolsWhitelist", taskService.toolsOf(task));
        m.put("conversationId", task.getConversationId());
        m.put("nextRunAt", task.getNextRunAt());
        m.put("lastRunAt", task.getLastRunAt());
        m.put("createdAt", task.getCreatedAt());
        m.put("updatedAt", task.getUpdatedAt());
        m.put("lastRun", lastRun == null ? null : Map.of(
                "status", lastRun.getStatus(),
                "finishedAt", lastRun.getFinishedAt() == null ? "" : lastRun.getFinishedAt(),
                "durationMs", lastRun.getDurationMs() == null ? 0L : lastRun.getDurationMs(),
                "errorMessage", lastRun.getErrorMessage() == null ? "" : lastRun.getErrorMessage(),
                "assistantMessageId", lastRun.getAssistantMessageId() == null ? 0L : lastRun.getAssistantMessageId()));
        return m;
    }

    private static Map<String, Object> runToMap(ScheduledTaskRunEntity run) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", run.getId());
        m.put("status", run.getStatus());
        m.put("traceId", run.getTraceId());
        m.put("durationMs", run.getDurationMs());
        m.put("errorMessage", run.getErrorMessage());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        m.put("assistantMessageId", run.getAssistantMessageId());
        return m;
    }
}