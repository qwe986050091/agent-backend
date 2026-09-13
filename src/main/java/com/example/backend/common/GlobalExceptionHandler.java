package com.example.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：统一错误响应格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数/业务校验错误 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }

    /** 反馈目标不存在或不属于当前用户（P2-11.1） */
    @ExceptionHandler(com.example.backend.feedback.FeedbackTraceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleFeedbackTraceNotFound(
            com.example.backend.feedback.FeedbackTraceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "FEEDBACK_TRACE_NOT_FOUND", "message", e.getMessage()));
    }

    /** 兜底：未预期异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("[GlobalExceptionHandler] 服务器异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", "服务器内部错误: " + e.getMessage()));
    }
}
