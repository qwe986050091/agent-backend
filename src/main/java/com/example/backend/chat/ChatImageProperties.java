package com.example.backend.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话图片（多模态 P1-8）配置：app.chat.image.* 段。
 *
 * 阈值需要同时满足两条物理链路：
 * - nginx client_max_body_size 25m（知识库上传已放开）：3 张 ×5MB 原图 base64 ≈20MB，安全；
 * - DeepSeek 内联图片单图 ≤32MiB / 请求体 ≤48MiB。
 */
@ConfigurationProperties(prefix = "app.chat.image")
public class ChatImageProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 单条消息最多图片数 */
    private int maxCount = 3;

    /** 单张图片原始字节上限（base64 解码后），默认 5MB */
    private long maxSizeBytes = 5L * 1024 * 1024;

    /** 图片落盘根目录（生产经 CHAT_IMAGE_DIR 指向 /opt/agent-data/chat-images） */
    private String dir = "data/chat-images";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxCount() { return maxCount; }
    public void setMaxCount(int maxCount) { this.maxCount = maxCount; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
}
