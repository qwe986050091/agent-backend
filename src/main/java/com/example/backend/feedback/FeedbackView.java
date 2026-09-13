package com.example.backend.feedback;

/**
 * 反馈状态视图（history 水合 / 提交响应）。
 *
 * @param rating     1=赞，-1=踩
 * @param reasonCode 点踩原因码（赞时为 null）
 * @param comment    补充说明（可为 null）
 */
public record FeedbackView(int rating, String reasonCode, String comment) {

    public static FeedbackView of(MessageFeedbackEntity e) {
        return new FeedbackView(e.getRating(), e.getReasonCode(), e.getComment());
    }
}
