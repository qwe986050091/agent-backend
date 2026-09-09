package com.example.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置（application.yml 中 auth.* 段）
 */
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** 会话有效期（秒）：每次携带有效 token 的请求滑动续期，到期后 401 自动跳登录 */
    private long sessionTtlSeconds = 7L * 24 * 3600;

    /** 登录失败计数窗口（秒） */
    private long failWindowSeconds = 600;

    /** 窗口内最大失败次数，超过即锁定 */
    private int maxFailures = 5;

    /** 失败锁定持续时间（秒） */
    private long lockSeconds = 900;

    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public void setSessionTtlSeconds(long sessionTtlSeconds) { this.sessionTtlSeconds = sessionTtlSeconds; }
    public long getFailWindowSeconds() { return failWindowSeconds; }
    public void setFailWindowSeconds(long failWindowSeconds) { this.failWindowSeconds = failWindowSeconds; }
    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
    public long getLockSeconds() { return lockSeconds; }
    public void setLockSeconds(long lockSeconds) { this.lockSeconds = lockSeconds; }
}
