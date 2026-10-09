package com.example.backend.agent;

import com.example.backend.agent.AgentClient.AgentChatImage;
import com.example.backend.agent.AgentClient.AgentChatMessage;
import com.example.backend.agent.AgentClient.AgentChatPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：发给 agent 的图片 JSON 字段必须是 data（agent 端 ChatImage 的字段名），
 * 不是 record 组件名 base64Data。字段不一致曾导致 agent data=null 校验失败，
 * 在 produces=text/event-stream 端点上错误体无法序列化，最终对用户表现为 500。
 */
class AgentChatImageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void imageSerializesWithDataKey() throws Exception {
        AgentChatImage image = new AgentChatImage("image/png", "aGVsbG8=");

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(image));

        assertThat(node.has("mimeType")).isTrue();
        assertThat(node.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(node.has("data")).isTrue();
        assertThat(node.get("data").asText()).isEqualTo("aGVsbG8=");
        assertThat(node.has("base64Data")).isFalse();
    }

    @Test
    void payloadImagesAndHistoryUseDataKey() throws Exception {
        AgentChatPayload payload = new AgentChatPayload(
                "zlf", 99004L, "看看这张图", null,
                List.of(new AgentChatMessage("user", "历史图片",
                        List.of(new AgentChatImage("image/jpeg", "aGlzdA==")))),
                List.of(), List.of(),
                List.of(new AgentChatImage("image/webp", "bm93")));

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        JsonNode current = root.path("images").get(0);
        assertThat(current.path("data").asText()).isEqualTo("bm93");
        assertThat(current.has("base64Data")).isFalse();

        JsonNode historyImg = root.path("history").get(0).path("images").get(0);
        assertThat(historyImg.path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(historyImg.path("data").asText()).isEqualTo("aGlzdA==");
    }
}
