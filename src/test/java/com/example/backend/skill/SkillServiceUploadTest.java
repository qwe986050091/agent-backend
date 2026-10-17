package com.example.backend.skill;

import com.example.backend.agent.AgentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService} 校验与持久化规则单测（P2-12）：
 *   1. 上传校验与 toolNamesJson 序列化（MySQL 是技能元数据的唯一权威）
 *   2. 挂载/卸载/删除的分支行为
 *   3. agent 不可达时的防御性降级（不阻塞启动/不抛给调用方）
 *
 * 纯单测：仓库与 AgentClient 全部 mock，不连 MySQL/不连 agent。
 */
class SkillServiceUploadTest {

    private SkillRepository skillRepository;
    private UserSkillRepository userSkillRepository;
    private AgentClient agentClient;
    private SkillService service;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        userSkillRepository = mock(UserSkillRepository.class);
        agentClient = mock(AgentClient.class);
        service = new SkillService(skillRepository, userSkillRepository, agentClient);
    }

    // ==================== 上传：校验 ====================

    @Test
    void blankNameIsRejectedBeforePersisting() {
        assertThatThrownBy(() -> service.upload(req(null, List.of()), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称不能为空");

        assertThatThrownBy(() -> service.upload(req("   ", List.of()), "u1"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(skillRepository, never()).save(any());
    }

    @Test
    void builtinNameCannotBeOverwritten() {
        SkillEntity builtin = new SkillEntity();
        builtin.setName("calculator");
        builtin.setBuiltin(true);
        when(skillRepository.findByName("calculator")).thenReturn(Optional.of(builtin));

        assertThatThrownBy(() -> service.upload(req("calculator", List.of()), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内置");

        verify(skillRepository, never()).save(any());
    }

    // ==================== 上传：落库 ====================

    @Test
    void nameIsTrimmedAndCustomSkillIsCreated() {
        when(skillRepository.findByName("report")).thenReturn(Optional.empty());

        SkillEntity saved = service.upload(req("  report  ", List.of("toolA", "toolB")), "u1");

        assertThat(saved.getName()).isEqualTo("report");
        assertThat(saved.getShortDescription()).isEqualTo("短描述");
        assertThat(saved.getDetailedDescription()).isEqualTo("详述");
        assertThat(saved.getSystemPrompt()).isEqualTo("提示");
        // toolNames 以 JSON 数组存库，供下发给 agent 时反序列化
        assertThat(saved.getToolNamesJson()).isEqualTo("[\"toolA\",\"toolB\"]");
        assertThat(saved.isBuiltin()).isFalse();
        assertThat(saved.getCreatedBy()).isEqualTo("u1");
        verify(skillRepository).save(saved);
    }

    @Test
    void nullToolNamesSerializeAsEmptyArray() {
        when(skillRepository.findByName("x")).thenReturn(Optional.empty());

        SkillEntity saved = service.upload(new SkillUploadRequest("x", null, null, null, null), "u1");

        assertThat(saved.getToolNamesJson()).isEqualTo("[]");
    }

    @Test
    void existingCustomSkillIsUpdatedInPlace() {
        SkillEntity existing = new SkillEntity();
        existing.setId(42L);
        existing.setName("report");
        existing.setBuiltin(false);
        when(skillRepository.findByName("report")).thenReturn(Optional.of(existing));

        SkillEntity saved = service.upload(req("report", List.of("toolC")), "u2");

        // 复用同一行（不新建），覆盖字段
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getToolNamesJson()).isEqualTo("[\"toolC\"]");
        assertThat(saved.getCreatedBy()).isEqualTo("u2");
    }

    // ==================== 上传：技能类型（Agent-as-Tool） ====================

    @Test
    void typeDefaultsToToolWhenAbsent() {
        when(skillRepository.findByName("x")).thenReturn(Optional.empty());

        SkillEntity saved = service.upload(
                new SkillUploadRequest("x", null, null, null, null, null), "u1");

        assertThat(saved.getType()).isEqualTo(SkillEntity.TYPE_TOOL);
    }

    @Test
    void agentTypeIsPersisted() {
        when(skillRepository.findByName("researcher")).thenReturn(Optional.empty());

        SkillEntity saved = service.upload(new SkillUploadRequest(
                "researcher", "研究", "详述", "你是研究员", List.of("webSearchTools"), "agent"), "u1");

        assertThat(saved.getType()).isEqualTo(SkillEntity.TYPE_AGENT);
        assertThat(saved.getToolNamesJson()).isEqualTo("[\"webSearchTools\"]");
    }

    @Test
    void unknownTypeIsRejectedBeforePersisting() {
        when(skillRepository.findByName("x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                new SkillUploadRequest("x", null, null, null, null, "robot"), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool/agent");

        verify(skillRepository, never()).save(any());
    }

    // ==================== 挂载 / 卸载 / 删除 ====================

    @Test
    void mountReturnsFalseWhenSkillMissing() {
        when(skillRepository.findByName("ghost")).thenReturn(Optional.empty());

        assertThat(service.mount("u1", "ghost")).isFalse();
        verify(userSkillRepository, never()).save(any());
    }

    @Test
    void mountSkipsWriteWhenAlreadyMounted() {
        when(skillRepository.findByName("calculator")).thenReturn(Optional.of(new SkillEntity()));
        when(userSkillRepository.existsByUserIdAndSkillName("u1", "calculator")).thenReturn(true);

        assertThat(service.mount("u1", "calculator")).isTrue();
        verify(userSkillRepository, never()).save(any());
    }

    @Test
    void mountCreatesRelationWhenAbsent() {
        when(skillRepository.findByName("datetime")).thenReturn(Optional.of(new SkillEntity()));
        when(userSkillRepository.existsByUserIdAndSkillName("u1", "datetime")).thenReturn(false);

        assertThat(service.mount("u1", "datetime")).isTrue();

        ArgumentCaptor<UserSkillEntity> captor = ArgumentCaptor.forClass(UserSkillEntity.class);
        verify(userSkillRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getSkillName()).isEqualTo("datetime");
    }

    @Test
    void unmountReturnsFalseWhenNotMounted() {
        when(userSkillRepository.existsByUserIdAndSkillName("u1", "x")).thenReturn(false);

        assertThat(service.unmount("u1", "x")).isFalse();
        verify(userSkillRepository, never()).deleteByUserIdAndSkillName(anyString(), anyString());
    }

    @Test
    void unmountDeletesExistingRelation() {
        when(userSkillRepository.existsByUserIdAndSkillName("u1", "x")).thenReturn(true);

        assertThat(service.unmount("u1", "x")).isTrue();
        verify(userSkillRepository).deleteByUserIdAndSkillName("u1", "x");
    }

    @Test
    void deleteRefusesMissingAndBuiltinSkills() {
        assertThat(service.delete("ghost")).isFalse();

        SkillEntity builtin = new SkillEntity();
        builtin.setBuiltin(true);
        when(skillRepository.findByName("calculator")).thenReturn(Optional.of(builtin));
        assertThat(service.delete("calculator")).isFalse();

        verify(skillRepository, never()).delete(any());
    }

    @Test
    void deleteCustomSkillRemovesMountsThenEntity() {
        SkillEntity custom = new SkillEntity();
        custom.setName("report");
        custom.setBuiltin(false);
        when(skillRepository.findByName("report")).thenReturn(Optional.of(custom));

        assertThat(service.delete("report")).isTrue();

        verify(userSkillRepository).deleteBySkillName("report");
        verify(skillRepository).delete(custom);
    }

    @Test
    void mountedNamesDeduplicatesAndKeepsOrder() {
        when(userSkillRepository.findByUserId("u1")).thenReturn(List.of(
                mountRow("u1", "calculator"), mountRow("u1", "web"), mountRow("u1", "calculator")));

        assertThat(service.mountedNames("u1")).containsExactly("calculator", "web");
    }

    // ==================== agent 侧数据解析与防御 ====================

    @Test
    void availableToolsAreSortedAndFailSafe() {
        when(agentClient.listToolNames()).thenReturn(Set.of("webSearchTools", "calculatorTools"));
        assertThat(service.availableTools()).containsExactly("calculatorTools", "webSearchTools");

        // agent 不可达时降级为空列表，不把异常抛给调用方
        when(agentClient.listToolNames()).thenThrow(new IllegalStateException("agent down"));
        assertThat(service.availableTools()).isEmpty();
    }

    @Test
    void syncBuiltinCatalogSkipsWhenAgentUnreachable() {
        when(agentClient.listBuiltinSkills()).thenReturn(null);

        service.syncBuiltinCatalog();

        verify(skillRepository, never()).save(any());
        verify(skillRepository, never()).delete(any());
    }

    @Test
    void syncBuiltinCatalogInsertsMissingRowsAndDropsStaleMcpRows() {
        when(agentClient.listBuiltinSkills()).thenReturn(List.of(
                Map.of("name", "calculator", "shortDescription", "算数"),
                Map.of("name", "mcp:weather", "shortDescription", "天气 MCP")));
        when(skillRepository.findByName("calculator")).thenReturn(Optional.of(new SkillEntity()));
        when(skillRepository.findByName("mcp:weather")).thenReturn(Optional.empty());

        SkillEntity stale = new SkillEntity();
        stale.setName("mcp:legacy");
        stale.setBuiltin(true);
        when(skillRepository.findByBuiltinTrueAndNameStartingWith("mcp:")).thenReturn(List.of(stale));

        service.syncBuiltinCatalog();

        // 缺失的 mcp 行补建
        ArgumentCaptor<SkillEntity> saved = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("mcp:weather");
        assertThat(saved.getValue().isBuiltin()).isTrue();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("system");
        // 已下线的 mcp 行连同挂载关系一起清理
        verify(userSkillRepository).deleteBySkillName("mcp:legacy");
        verify(skillRepository).delete(stale);
    }

    // ==================== 夹具 ====================

    private static SkillUploadRequest req(String name, List<String> toolNames) {
        return new SkillUploadRequest(name, "短描述", "详述", "提示", toolNames);
    }

    private static UserSkillEntity mountRow(String userId, String skillName) {
        UserSkillEntity row = new UserSkillEntity();
        row.setUserId(userId);
        row.setSkillName(skillName);
        return row;
    }
}