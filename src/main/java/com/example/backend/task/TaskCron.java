package com.example.backend.task;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 定时任务 cron 解析/校验/下次执行计算（P2-9）。
 *
 * 统一约定：Spring 6 段 cron（秒 分 时 日 月 周），计划时区固定 {@code app.task.zone}；
 * 所有计算均在业务时区（默认 Asia/Shanghai）墙钟上进行，与操作系统时区解耦。
 */
final class TaskCron {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TaskCron() {}

    /**
     * 解析并校验 cron 表达式：
     * ① 必须 6 段；② 语法合法；③ 前 5 次执行的最小间隔 ≥ minIntervalMinutes（防 {@code *&#47;1} 类烧钱表达式）。
     *
     * @return 规整（trim）后的表达式
     * @throws IllegalArgumentException 校验失败（中文可读文案，全局异常处理映射 400）
     */
    static String validate(String expr, ZoneId zone, int minIntervalMinutes) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        String trimmed = expr.trim();
        if (trimmed.split("\\s+").length != 6) {
            throw new IllegalArgumentException(
                    "cron 需要 6 段（秒 分 时 日 月 周），例如「0 0 8 * * *」= 每天 08:00");
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式非法：" + e.getMessage());
        }
        ZonedDateTime cursor = ZonedDateTime.now(zone);
        ZonedDateTime prev = null;
        for (int i = 0; i < 5; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) {
                throw new IllegalArgumentException("cron 表达式在未来几年内没有执行时间，请检查日期字段");
            }
            if (prev != null && Duration.between(prev, cursor).toMinutes() < minIntervalMinutes) {
                throw new IllegalArgumentException("执行过于频繁：前 5 次计划最小间隔需 ≥ " + minIntervalMinutes
                        + " 分钟（" + prev.format(MINUTE_FMT) + " → " + cursor.format(MINUTE_FMT) + " 间隔不足）");
            }
            prev = cursor;
        }
        return trimmed;
    }

    /**
     * 从 from（业务时区墙钟）起算下一次执行时间。
     * cron 非法/无下次执行返回 null（正常创建路径已校验，这里只做防御）。
     */
    static LocalDateTime nextAfter(String expr, LocalDateTime from, ZoneId zone) {
        if (expr == null || expr.isBlank() || from == null) {
            return null;
        }
        try {
            ZonedDateTime next = CronExpression.parse(expr.trim()).next(from.atZone(zone));
            return next == null ? null : next.toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}