package com.example.backend.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户级限流与配额配置（application.yml 中 ratelimit.* 段，P0-4）。
 *
 * 三道闸门均按用户名维度，0 表示关闭对应闸门；整体开关 {@link #enabled}。
 * 生产阈值经 systemd Environment（RATELIMIT_*）注入，无需改代码/重新打包。
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** 总开关；false 时所有闸门跳过（应急降级用） */
    private boolean enabled = true;

    /** 每用户每分钟最大对话请求数（同步 + 流式合计），0=不限 */
    private int requestsPerMinute = 20;

    /** 每用户同时在途对话数（同步调用 + SSE 连接合计），0=不限 */
    private int maxConcurrent = 4;

    /**
     * 每用户每日 token 总预算（输入 + 输出合计，按 llm_usage_record 当日聚合）。
     * 0=不限制。超额后当日不再放行新回合（被拒请求本身不产生 token 费用）。
     */
    private long dailyTokenBudget = 2_000_000L;

    /**
     * 在途并发键的兜底 TTL（秒）。正常路径在回合结束时 DECR 释放；
     * 进程崩溃/节点宕机导致未释放时，残留计数最多存活该时长，必须大于 SSE 总超时（5 分钟）。
     */
    private long concurrentTtlSeconds = 360;

    /** 管理员（app.admin.usernames 白名单）是否豁免全部限额 */
    private boolean exemptAdmins = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public long getDailyTokenBudget() { return dailyTokenBudget; }
    public void setDailyTokenBudget(long dailyTokenBudget) { this.dailyTokenBudget = dailyTokenBudget; }
    public long getConcurrentTtlSeconds() { return concurrentTtlSeconds; }
    public void setConcurrentTtlSeconds(long concurrentTtlSeconds) { this.concurrentTtlSeconds = concurrentTtlSeconds; }
    public boolean isExemptAdmins() { return exemptAdmins; }
    public void setExemptAdmins(boolean exemptAdmins) { this.exemptAdmins = exemptAdmins; }
}
