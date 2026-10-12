package com.example.backend.task;

import java.util.List;

/**
 * 定时任务创建/更新请求体（P2-9，PATCH 语义：字段为 null 表示不修改）。
 *
 * @param name           任务名（≤100 字）
 * @param prompt         任务指令（≤4000 字；执行时自动前缀注入当前时间）
 * @param cronExpr       Spring 6 段 cron（秒 分 时 日 月 周）
 * @param toolsWhitelist 任务级预授权工具（勾选=该任务可自动执行这些工具，无需用户确认）
 * @param enabled        启用开关（创建时缺省 true）
 */
public record TaskRequest(String name, String prompt, String cronExpr,
                          List<String> toolsWhitelist, Boolean enabled) {
}