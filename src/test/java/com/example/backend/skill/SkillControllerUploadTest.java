package com.example.backend.skill;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillController} 请求编排单测（P2-12）：
 *   JSON / multipart 两条上传路径的入参校验、异常到 HTTP 状态码的映射，
 *   以及列表接口对 toolNamesJson 的容错解析。
 *
 * 不启 Spring：直接 new 控制器 + MockHttpServletRequest 注入登录用户。
 */
class SkillControllerUploadTest {

    private static final String VALID_JSON = """
            {
              "name": "weather_time_report",
              "shortDescription": "生成天气时间报告",
              "systemPrompt": "先取天气再取时间",
              "toolNames": ["webSearchTools", "dateTimeTools"]
            }
            """;

    private SkillService skillService;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        controller = new SkillController(skillService);
    }

    // ==================== JSON 上传 ====================

    @Test
    void uploadJsonRejectsBlankNameWith400() {
        ResponseEntity<?> resp = controller.uploadJson(
                new SkillUploadRequest("   ", null, null, null, null), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(resp)).containsEntry("code", "BAD_REQUEST");
    }

    @Test
    void uploadJsonMapsIllegalArgumentTo400InvalidSkill() {
        when(skillService.upload(any(), anyString()))
                .thenThrow(new IllegalArgumentException("内置 Skill 名称不可覆盖: calculator"));

        ResponseEntity<?> resp = controller.uploadJson(
                new SkillUploadRequest("calculator", null, null, null, List.of()), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(resp)).containsEntry("code", "INVALID_SKILL");
        assertThat(String.valueOf(body(resp).get("message"))).contains("内置");
    }

    @Test
    void uploadJsonMapsIllegalStateTo502AgentError() {
        when(skillService.upload(any(), anyString()))
                .thenThrow(new IllegalStateException("agent 不可达"));

        ResponseEntity<?> resp = controller.uploadJson(
                new SkillUploadRequest("report", null, null, null, List.of()), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body(resp)).containsEntry("code", "AGENT_ERROR");
    }

    @Test
    void uploadJsonReturnsUploadedName() {
        SkillEntity entity = new SkillEntity();
        entity.setName("report");
        when(skillService.upload(any(), anyString())).thenReturn(entity);

        ResponseEntity<?> resp = controller.uploadJson(
                new SkillUploadRequest("report", null, null, null, List.of()), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp)).containsEntry("name", "report");
    }

    // ==================== multipart 文件上传 ====================

    @Test
    void uploadFileRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", new byte[0]);

        ResponseEntity<?> resp = controller.uploadFile(empty, requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(body(resp).get("message"))).contains("上传文件不能为空");
    }

    @Test
    void uploadFileRejectsMalformedJson() {
        MockMultipartFile bad = new MockMultipartFile(
                "file", "skill.json", "application/json", "{ 这不是 JSON".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> resp = controller.uploadFile(bad, requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(body(resp).get("message"))).contains("Skill 文件解析失败");
    }

    @Test
    void uploadFileParsesJsonAndDelegatesToService() {
        MockMultipartFile ok = new MockMultipartFile(
                "file", "skill.json", "application/json", VALID_JSON.getBytes(StandardCharsets.UTF_8));
        SkillEntity entity = new SkillEntity();
        entity.setName("weather_time_report");
        when(skillService.upload(any(), anyString())).thenReturn(entity);

        ResponseEntity<?> resp = controller.uploadFile(ok, requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resp)).containsEntry("name", "weather_time_report");
    }

    // ==================== 列表：toolNamesJson 容错解析 ====================

    @Test
    void listParsesToolNamesJsonSafely() {
        when(skillService.mountedNames("u1")).thenReturn(Set.of("report"));
        when(skillService.listAll()).thenReturn(List.of(
                entity("report", "[\"webSearchTools\",\"dateTimeTools\"]", false, "u1"),
                entity("calculator", "[]", true, "system"),
                entity("no-json", null, false, "u1"),
                entity("broken-json", "{oops", false, "u1")));

        ResponseEntity<?> resp = controller.list(requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(resp).get("skills");
        assertThat(items).hasSize(4);

        assertThat(items.get(0)).containsEntry("mounted", true);
        assertThat(items.get(0).get("toolNames")).isEqualTo(List.of("webSearchTools", "dateTimeTools"));
        assertThat(items.get(1)).containsEntry("builtin", true).containsEntry("mounted", false);
        assertThat(items.get(1).get("toolNames")).isEqualTo(List.of());
        // 字段缺失 / 畸形 JSON 都降级为空列表，不抛异常
        assertThat(items.get(2).get("toolNames")).isEqualTo(List.of());
        assertThat(items.get(3).get("toolNames")).isEqualTo(List.of());
        assertThat(items.get(3)).containsEntry("createdBy", "u1");
    }

    // ==================== 挂载 / 卸载 / 删除的状态码 ====================

    @Test
    void mountRejectsBlankSkillName() {
        ResponseEntity<?> resp = controller.mount(Map.of(), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(resp)).containsEntry("code", "BAD_REQUEST");
    }

    @Test
    void mountReturns404WhenSkillMissing() {
        when(skillService.mount("u1", "ghost")).thenReturn(false);

        ResponseEntity<?> resp = controller.mount(Map.of("skillName", "ghost"), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(resp)).containsEntry("code", "SKILL_NOT_FOUND");
    }

    @Test
    void unmountReturns404WhenNotMounted() {
        when(skillService.unmount("u1", "calculator")).thenReturn(false);

        ResponseEntity<?> resp = controller.unmount(Map.of("skillName", "calculator"), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(resp)).containsEntry("code", "NOT_MOUNTED");
    }

    @Test
    void deleteReturns404WhenNotDeletable() {
        when(skillService.delete("calculator")).thenReturn(false);

        ResponseEntity<?> resp = controller.delete("calculator");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(resp)).containsEntry("code", "NOT_DELETABLE");
    }

    // ==================== 技能类型（Agent-as-Tool） ====================

    @Test
    void listIncludesSkillTypeWithToolDefault() {
        when(skillService.mountedNames("u1")).thenReturn(Set.of());
        SkillEntity agentSkill = entity("researcher", "[]", false, "u1");
        agentSkill.setType(SkillEntity.TYPE_AGENT);
        SkillEntity plain = entity("report", "[]", false, "u1"); // 未设 type → 兜底 tool
        when(skillService.listAll()).thenReturn(List.of(agentSkill, plain));

        ResponseEntity<?> resp = controller.list(requestAs("u1"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(resp).get("skills");
        assertThat(items.get(0)).containsEntry("type", SkillEntity.TYPE_AGENT);
        assertThat(items.get(1)).containsEntry("type", SkillEntity.TYPE_TOOL);
    }

    @Test
    void uploadJsonPassesAgentTypeThrough() {
        SkillEntity entity = new SkillEntity();
        entity.setName("researcher");
        entity.setType(SkillEntity.TYPE_AGENT);
        when(skillService.upload(any(), anyString())).thenReturn(entity);

        ResponseEntity<?> resp = controller.uploadJson(
                new SkillUploadRequest("researcher", null, null, null, List.of(), "agent"), requestAs("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<SkillUploadRequest> captor = ArgumentCaptor.forClass(SkillUploadRequest.class);
        verify(skillService).upload(captor.capture(), anyString());
        assertThat(captor.getValue().type()).isEqualTo("agent");
    }

    // ==================== 夹具 ====================

    /** 登录态由 AuthInterceptor 注入 request attribute，控制器据此取用户名 */
    private static MockHttpServletRequest requestAs(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserEntity user = new UserEntity();
        user.setUsername(username);
        request.setAttribute(AuthInterceptor.CURRENT_USER_ATTR, user);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    private static SkillEntity entity(String name, String toolNamesJson, boolean builtin, String createdBy) {
        SkillEntity e = new SkillEntity();
        e.setName(name);
        e.setToolNamesJson(toolNamesJson);
        e.setBuiltin(builtin);
        e.setCreatedBy(createdBy);
        return e;
    }
}