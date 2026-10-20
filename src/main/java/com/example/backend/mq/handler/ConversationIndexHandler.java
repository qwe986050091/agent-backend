package com.example.backend.mq.handler;

import com.example.backend.agent.AgentClient;
import com.example.backend.mq.EventHandler;
import com.example.backend.mq.EventTopics;
import com.example.backend.mq.event.ConversationIndexEvent;
import org.springframework.stereotype.Component;

/**
 * 对话索引消费者：调用 agent 把消息写入 RAG 长期记忆向量库。
 *
 * 替代改造前的进程内异步线程：进程重启不再丢任务，agent 短暂不可用时消息留在队列重试。
 * 幂等性：同一内容重复索引会产生重复向量，但检索按 id 去重且片段内容一致，影响可接受。
 */
@Component
public class ConversationIndexHandler implements EventHandler<ConversationIndexEvent> {

    private final AgentClient agentClient;

    public ConversationIndexHandler(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    @Override
    public Class<ConversationIndexEvent> type() {
        return ConversationIndexEvent.class;
    }

    @Override
    public String topic() {
        return EventTopics.CONVERSATION_INDEX;
    }

    @Override
    public void handle(ConversationIndexEvent event) throws Exception {
        if (event == null || event.userId() == null || event.content() == null
                || event.content().isBlank()) {
            return;
        }
        agentClient.indexConversation(event.userId(), event.role(), event.content());
    }
}
