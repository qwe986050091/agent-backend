package com.example.backend.chat;

import java.util.List;

/**
 * 对话发送请求体（P1-8 起替代裸 Map：同步 /api/chat 与流式 /api/chat/stream 共用）。
 *
 * @param message 文本内容（允许为空，但与 images 不能同时为空——纯图片回合合法）
 * @param images  图片列表（mimeType=image/jpeg 等；data=原始 base64，不含 data: 前缀）
 */
public record ChatSendRequest(String message, String conversationId, List<ImageInput> images) {

    public record ImageInput(String mimeType, String data) {}
}
