package com.example.backend.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentClient 错误响应处理纯逻辑：
 * - readBodyLimited 限量读取错误体
 * - describeAgentError 把 agent 的 JSON 错误体提炼成可读后缀（流开始前 4xx/503 场景）
 */
class AgentClientErrorParsingTest {

    @Test
    void describe_jsonWithCodeAndMessage() {
        String body = "{\"code\":\"INVALID_IMAGE\",\"message\":\"仅支持 JPEG/PNG/GIF/WebP 图片\"}";
        assertThat(AgentClient.describeAgentError(body))
                .isEqualTo("：[INVALID_IMAGE] 仅支持 JPEG/PNG/GIF/WebP 图片");
    }

    @Test
    void describe_jsonWithMessageOnly() {
        assertThat(AgentClient.describeAgentError("{\"message\":\"bad\"}"))
                .isEqualTo("：bad");
    }

    @Test
    void describe_blankAndNullYieldEmpty() {
        assertThat(AgentClient.describeAgentError(null)).isEmpty();
        assertThat(AgentClient.describeAgentError("  ")).isEmpty();
        assertThat(AgentClient.describeAgentError("{\"code\":\"X\"}")).isEmpty();
    }

    @Test
    void describe_nonJsonFallsBackToRawTruncated() {
        assertThat(AgentClient.describeAgentError("<html>502 Bad Gateway</html>"))
                .isEqualTo("：<html>502 Bad Gateway</html>");
        String longBody = "y".repeat(900);
        assertThat(AgentClient.describeAgentError(longBody)).hasSize(501);
    }

    @Test
    void readBody_limitedAndNullSafe() {
        ByteArrayInputStream in = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(AgentClient.readBodyLimited(in, 3)).isEqualTo("hel");
        assertThat(AgentClient.readBodyLimited(null, 100)).isEmpty();
    }
}
