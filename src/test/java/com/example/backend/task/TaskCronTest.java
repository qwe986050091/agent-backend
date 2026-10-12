package com.example.backend.task;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TaskCron} 单元测试：6 段校验、语法校验、最小间隔防呆、下次执行时间计算（业务时区）。
 */
class TaskCronTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Test
    void validate_rejectsFiveFieldExpression() {
        assertThatThrownBy(() -> TaskCron.validate("0 8 * * *", SH, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6 段");
    }

    @Test
    void validate_rejectsInvalidSyntax() {
        assertThatThrownBy(() -> TaskCron.validate("0 0 8 * * 99", SH, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法");
    }

    @Test
    void validate_rejectsTooFrequentExpression() {
        // 每 1 秒：首个间隔 1s < 5 分钟
        assertThatThrownBy(() -> TaskCron.validate("*/1 * * * * *", SH, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过于频繁");
        // 每 4 分钟：4min < 5min
        assertThatThrownBy(() -> TaskCron.validate("0 */4 * * * *", SH, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过于频繁");
    }

    @Test
    void validate_acceptsFiveMinuteIntervalAndDailyExpressions() {
        assertThat(TaskCron.validate("0 */5 * * * *", SH, 5)).isEqualTo("0 */5 * * * *");
        assertThat(TaskCron.validate("  0 0 8 * * *  ", SH, 5)).isEqualTo("0 0 8 * * *");
        assertThat(TaskCron.validate("0 0 9 * * MON", SH, 5)).isEqualTo("0 0 9 * * MON");
    }

    @Test
    void nextAfter_dailyEightAm_shanghaiWallClock() {
        // 今天 07:30（上海墙钟）→ 下次 08:00
        LocalDateTime from = LocalDateTime.of(2026, 10, 12, 7, 30);
        LocalDateTime next = TaskCron.nextAfter("0 0 8 * * *", from, SH);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 10, 12, 8, 0));

        // 08:00 整点已过（next 是严格未来）→ 次日 08:00
        LocalDateTime after = TaskCron.nextAfter("0 0 8 * * *", LocalDateTime.of(2026, 10, 12, 8, 0), SH);
        assertThat(after).isEqualTo(LocalDateTime.of(2026, 10, 13, 8, 0));
    }

    @Test
    void nextAfter_everyFiveMinutes() {
        LocalDateTime next = TaskCron.nextAfter("0 */5 * * * *", LocalDateTime.of(2026, 10, 12, 8, 3), SH);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 10, 12, 8, 5));
    }

    @Test
    void nextAfter_invalidExpression_returnsNull() {
        assertThat(TaskCron.nextAfter("not-a-cron", LocalDateTime.now(), SH)).isNull();
        assertThat(TaskCron.nextAfter(null, LocalDateTime.now(), SH)).isNull();
    }
}