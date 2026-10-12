package com.example.backend.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务配置（application.yml 中 app.task.* 段，P2-9）。
 *
 * 全部参数可经 systemd Environment（APP_TASK_*）覆盖，生产默认值即生效；
 * {@link #enabled}=false 是回滚手段：完全停调度、任务数据保留、表无害。
 */
@ConfigurationProperties(prefix = "app.task")
public class TaskProperties {

    /** 总开关；false 时 poller 不扫描、run-now 拒绝投递（保留数据） */
    private boolean enabled = true;

    /** poller 扫描周期（秒） */
    private int pollIntervalSeconds = 30;

    /** 每用户启用任务上限（创建/启用时校验） */
    private int maxPerUser = 10;

    /** cron 前 5 次执行的最小间隔（分钟），防每秒级高频表达式烧钱 */
    private int minIntervalMinutes = 5;

    /** 每任务每日执行上限（含 run-now），超额跳过投递 */
    private int maxRunsPerDay = 24;

    /** 执行池最大并发（同时执行的定时任务数） */
    private int maxConcurrentRuns = 2;

    /** 计划时区（cron 解析 / next_run_at / 每日窗口均按此时区） */
    private String zone = "Asia/Shanghai";

    /** Redis 任务锁兜底 TTL（秒）：跨「认领后进程崩溃」残留 */
    private long lockTtlSeconds = 900;

    /** 僵尸执行判定（分钟）：running 超时未收尾的旧行标 failed */
    private int zombieTimeoutMinutes = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    public int getMaxPerUser() { return maxPerUser; }
    public void setMaxPerUser(int maxPerUser) { this.maxPerUser = maxPerUser; }
    public int getMinIntervalMinutes() { return minIntervalMinutes; }
    public void setMinIntervalMinutes(int minIntervalMinutes) { this.minIntervalMinutes = minIntervalMinutes; }
    public int getMaxRunsPerDay() { return maxRunsPerDay; }
    public void setMaxRunsPerDay(int maxRunsPerDay) { this.maxRunsPerDay = maxRunsPerDay; }
    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public long getLockTtlSeconds() { return lockTtlSeconds; }
    public void setLockTtlSeconds(long lockTtlSeconds) { this.lockTtlSeconds = lockTtlSeconds; }
    public int getZombieTimeoutMinutes() { return zombieTimeoutMinutes; }
    public void setZombieTimeoutMinutes(int zombieTimeoutMinutes) { this.zombieTimeoutMinutes = zombieTimeoutMinutes; }
}