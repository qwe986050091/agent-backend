package com.example.backend.skill;

import java.util.List;

/**
 * 上传 Skill 的请求体（与 agent 工程的 SkillDefinition JSON 字段保持一致，
 * 因此可直接序列化后转发给 agent 的 /api/agent/skills/upload）
 *
 * type：技能类型 tool（默认，普通技能包）| agent（子代理：独立推理循环 + 私有工具）。
 * 缺省 / null 时按 tool 处理。
 */
public record SkillUploadRequest(
        String name,
        String shortDescription,
        String detailedDescription,
        String systemPrompt,
        List<String> toolNames,
        String type
) {

    /** 兼容旧五参构造（type 缺省 → 按 tool 处理），保持既有调用方/测试不变 */
    public SkillUploadRequest(String name, String shortDescription, String detailedDescription,
                              String systemPrompt, List<String> toolNames) {
        this(name, shortDescription, detailedDescription, systemPrompt, toolNames, null);
    }
}

