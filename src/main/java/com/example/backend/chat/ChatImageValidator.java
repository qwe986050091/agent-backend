package com.example.backend.chat;

import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Set;

/**
 * 对话图片校验（P1-8，backend 权威闸门；agent 侧另有防御性二次校验）。
 *
 * 校验项：开关、数量、base64 可解码、解码后大小、声明 MIME 白名单、
 * 以及<b>文件头 magic bytes 与声明类型一致性</b>（DeepSeek 按实际内容判格式，
 * 仅信 MIME 会让伪造后缀的非图片进入模型请求）。
 */
@Component
public class ChatImageValidator {

    public static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private final ChatImageProperties props;

    public ChatImageValidator(ChatImageProperties props) {
        this.props = props;
    }

    /** 一张通过校验的图片：规整后的 MIME + 解码字节 */
    public record ValidImage(String mimeType, byte[] bytes) {}

    /** 校验失败：返回错误码 + 中文文案；调用方映射为 400/413 */
    public record Failure(String code, String message) {}

    /**
     * 校验整批图片入参（null/空列表返回 null=无图片）。
     *
     * @param inputs 前端入参（mimeType + 原始 base64，允许带 data: 前缀）
     * @return 解码后的图片列表；失败信息从 {@link Failure} 抛出（unchecked 业务异常由全局处理映射）
     */
    public java.util.List<ValidImage> validate(java.util.List<ChatSendRequest.ImageInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return java.util.List.of();
        }
        if (!props.isEnabled()) {
            throw new ChatImageRejectedException("BAD_REQUEST", "当前未开启图片发送功能");
        }
        if (inputs.size() > props.getMaxCount()) {
            throw new ChatImageRejectedException("TOO_MANY_IMAGES",
                    "单条消息最多上传 " + props.getMaxCount() + " 张图片");
        }
        java.util.List<ValidImage> decoded = new java.util.ArrayList<>(inputs.size());
        for (ChatSendRequest.ImageInput input : inputs) {
            decoded.add(validateOne(input));
        }
        return decoded;
    }

    private ValidImage validateOne(ChatSendRequest.ImageInput input) {
        if (input == null || input.data() == null || input.data().isBlank()) {
            throw new ChatImageRejectedException("INVALID_IMAGE", "图片数据为空");
        }
        String mime = input.mimeType() == null ? "" : input.mimeType().trim().toLowerCase();
        if (!ALLOWED_MIME.contains(mime)) {
            throw new ChatImageRejectedException("INVALID_IMAGE", "仅支持 JPEG/PNG/GIF/WebP 格式图片");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripDataUrl(input.data()));
        } catch (IllegalArgumentException e) {
            throw new ChatImageRejectedException("INVALID_IMAGE", "图片 base64 数据非法");
        }
        if (bytes.length == 0) {
            throw new ChatImageRejectedException("INVALID_IMAGE", "图片内容为空");
        }
        if (bytes.length > props.getMaxSizeBytes()) {
            throw new ChatImageRejectedException("IMAGE_TOO_LARGE",
                    "单张图片不能超过 " + (props.getMaxSizeBytes() / 1024 / 1024) + "MB");
        }
        String detected = detectMime(bytes);
        if (detected == null || !detected.equals(mime)) {
            throw new ChatImageRejectedException("INVALID_IMAGE",
                    "图片实际格式与声明类型不符（仅支持 JPEG/PNG/GIF/WebP）");
        }
        return new ValidImage(mime, bytes);
    }

    /** 容错剥掉 data:image/xxx;base64, 前缀（前端通常已剥，双保险） */
    private static String stripDataUrl(String raw) {
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            return raw.substring(comma + 1);
        }
        return raw.trim();
    }

    /** 按文件头判定真实图片类型；非白名单格式返回 null */
    public static String detectMime(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        // GIF87a / GIF89a
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        // WebP: "RIFF" .... "WEBP"
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** 图片校验业务异常（GlobalExceptionHandler 映射 HTTP 状态） */
    public static class ChatImageRejectedException extends RuntimeException {
        private final String code;

        public ChatImageRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
