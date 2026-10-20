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

    /** 对话图片校验失败（P1-8 多模态）：超量 413，其余（格式/数据非法）400 */
    @ExceptionHandler(com.example.backend.chat.ChatImageValidator.ChatImageRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleChatImageRejected(
            com.example.backend.chat.ChatImageValidator.ChatImageRejectedException e) {
        HttpStatus status = "IMAGE_TOO_LARGE".equals(e.code())
                ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    /** 上传文件超过 multipart 大小上限（P1-9.2：知识库文档 20MB） */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("code", "FILE_TOO_LARGE", "message", "文件超过 20MB 大小上限"));
    }

    /** agent 内容安全闸门拒绝输入（P4-17）：返回干净 400 + 可读文案，不暴露命中规则 */
    @ExceptionHandler(com.example.backend.agent.AgentClient.AgentSafetyBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleSafetyBlocked(
            com.example.backend.agent.AgentClient.AgentSafetyBlockedException e) {
        String message = com.example.backend.agent.AgentClient.safetyBlockedUserMessage(e);
        return ResponseEntity.badRequest().body(Map.of(
                "code", "SAFETY_BLOCKED",
                "message", message == null || message.isBlank()
                        ? "消息未通过内容安全检查，请调整后重试" : message));
    }

    /**
     * SSE 客户端断开（P1-E）：长连接在浏览器关闭/切换页面时被中断，
     * 此时响应已不可写，不能再走 JSON 兜底（会二次报 HttpMessageNotWritable）。
     * 直接返回空 4xx，不打印 ERROR 日志，避免正常断连刷屏。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncNotUsable(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("[GlobalExceptionHandler] SSE 客户端已断开: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** 兜底：未预期异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("[GlobalExceptionHandler] 服务器异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", "服务器内部错误: " + e.getMessage()));
    }
}
