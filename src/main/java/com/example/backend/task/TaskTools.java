package com.example.backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 任务级预授权工具清单（scheduled_task.tools_whitelist JSON 列）的序列化/解析。
 * 列内容为 JSON 数组文本（与 prompt_safety_record.rule_hits 同惯例）。
 */
final class TaskTools {

    /** 单任务预授权工具上限（防异常客户端灌入超长清单） */
    private static final int MAX_TOOLS = 20;

    private TaskTools() {}

    /** 规整（trim/去重/去空）并序列化为 JSON 数组文本；空清单返回 null */
    static String serialize(List<String> tools, ObjectMapper objectMapper) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String name = tool.trim();
            if (name.length() > 100) {
                throw new IllegalArgumentException("工具名过长（最多 100 字符）：" + name);
            }
            clean.add(name);
            if (clean.size() > MAX_TOOLS) {
                throw new IllegalArgumentException("预授权工具最多 " + MAX_TOOLS + " 个");
            }
        }
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw new IllegalStateException("工具清单序列化失败: " + e.getMessage());
        }
    }

    /** 解析 JSON 数组文本；空/损坏返回空清单（执行时按无预授权处理） */
    static List<String> parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            List<String> tools = new ArrayList<>();
            for (Object item : raw) {
                if (item != null && !item.toString().isBlank()) {
                    tools.add(item.toString());
                }
            }
            return tools;
        } catch (Exception e) {
            return List.of();
        }
    }
}