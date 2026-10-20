package com.example.backend.mq;

/**
 * 事件主题常量（P1-E）。
 *
 * 生产机：RocketMQ 主题 = 前缀 + 这里的值；开发机：Redis Stream = 前缀 + 这里的值。
 */
public final class EventTopics {

    /** 对话消息 → RAG 长期记忆索引（原进程内异步线程） */
    public static final String CONVERSATION_INDEX = "conversation-index";

    /** agent 评测报告上报 → 落库 + 看板（原同步写库） */
    public static final String EVAL_INGEST = "eval-ingest";

    /** 站内通知 → 落库 + SSE 推送（原前端轮询） */
    public static final String NOTIFICATION = "notification";

    private EventTopics() {
    }
}
