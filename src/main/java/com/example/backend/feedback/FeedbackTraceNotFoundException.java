package com.example.backend.feedback;

/**
 * 反馈目标 trace 不存在或不属于当前用户 → 404（沿用 trace 回查的归属校验语义）。
 */
public class FeedbackTraceNotFoundException extends RuntimeException {

    public FeedbackTraceNotFoundException() {
        super("反馈的消息不存在或无权操作");
    }
}
