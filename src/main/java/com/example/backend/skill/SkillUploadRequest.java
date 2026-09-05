package com.example.backend.skill;

import java.util.List;

/**
 * 上传 Skill 的请求体（与 agent 工程的 SkillDefinition JSON 字段保持一致，
 * 因此可直接序列化后转发给 agent 的 /api/agent/skills/upload）
 */
public record SkillUploadRequest(
        String name,
        String shortDescription,
        String detailedDescription,
        String systemPrompt,
        List<String> toolNames
) {
}
