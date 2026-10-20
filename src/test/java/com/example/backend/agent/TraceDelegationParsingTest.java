package com.example.backend.agent;

import com.example.backend.agent.trace.TraceData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent ↔ backend trace 字段契约（P1-C）：委派节点与子代理 RAG 归属必须能原样解析。
 */
class TraceDelegationParsingTest {

    @Test
    void parsesDelegationsAndRagAgentAttribution() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("tools", List.of());
        resp.put("safeties", List.of());
        resp.put("retrievals", List.of(Map.of(
                "query", "退款政策",
                "agentId", "researcher",
                "segments", List.of(Map.of(
                        "docId", "doc-refund",
                        "source", "退款.md",
                        "type", "document",
                        "text", "七天无理由",
                        "score", 0.9)))));
        resp.put("delegations", List.of(
                Map.of("id", "p1", "agent", "planner", "task", "调研退款政策",
                        "success", true, "durationMs", 120, "tokens", 300),
                Map.of("id", "s1", "agent", "researcher", "parentId", "p1",
                        "task", "查资料", "success", true, "durationMs", 80, "tokens", 150),
                Map.of("id", "s2", "agent", "verifier", "parentId", "p1",
                        "task", "复核", "success", false, "error", "超时",
                        "durationMs", 200, "tokens", 0)));

        TraceData data = AgentClient.parseTraceData(resp, new ObjectMapper());

        assertThat(data.delegations()).hasSize(3);
        assertThat(data.delegations().get(0).agent()).isEqualTo("planner");
        assertThat(data.delegations().get(0).parentId()).isNull();
        assertThat(data.delegations().get(1).parentId()).isEqualTo("p1");
        assertThat(data.delegations().get(1).tokens()).isEqualTo(150);
        assertThat(data.delegations().get(2).success()).isFalse();
        assertThat(data.delegations().get(2).error()).isEqualTo("超时");
        assertThat(data.recalls()).hasSize(1);
        assertThat(data.recalls().get(0).agentId()).isEqualTo("researcher");
    }

    @Test
    void missingDelegationsDegradesToEmpty() {
        Map<String, Object> resp = new HashMap<>();

        TraceData data = AgentClient.parseTraceData(resp, new ObjectMapper());

        assertThat(data.delegations()).isEmpty();
        assertThat(data.isEmpty()).isTrue();
    }
}
