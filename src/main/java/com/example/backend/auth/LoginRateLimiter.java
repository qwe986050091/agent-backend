package com.example.backend.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流（Redis 计数，防暴力破解）
 *
 * 按「用户名」与「IP」双维度计数：窗口内失败次数达到上限即锁定一段时间，
 * 锁定期内登录/注册直接返回 429。登录成功清空对应计数。
 */
@Component
public class LoginRateLimiter {

    private static final String FAIL_PREFIX = "auth:fail:";
    private static final String LOCK_PREFIX = "auth:lock:";

    private final StringRedisTemplate redis;
    private final AuthProperties props;

    public LoginRateLimiter(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 是否被锁定（用户名或 IP 任一维度） */
    public boolean isLocked(String username, String ip) {
        return isLockedByUser(username) || isLockedByIp(ip);
    }

    public boolean isLockedByUser(String username) {
        return username != null && Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + "user:" + username));
    }

    public boolean isLockedByIp(String ip) {
        return ip != null && Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + "ip:" + ip));
    }

    /** 记录一次失败；达到上限则加锁并清空计数（避免锁到期后旧计数残留） */
    public void recordFailure(String username, String ip) {
        if (username != null) {
            countUp(FAIL_PREFIX + "user:" + username);
        }
        if (ip != null) {
            countUp(FAIL_PREFIX + "ip:" + ip);
        }
    }

    /** 登录成功：清空失败计数 */
    public void recordSuccess(String username, String ip) {
        if (username != null) {
            redis.delete(FAIL_PREFIX + "user:" + username);
        }
        if (ip != null) {
            redis.delete(FAIL_PREFIX + "ip:" + ip);
        }
    }

    private void countUp(String failKey) {
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, Duration.ofSeconds(props.getFailWindowSeconds()));
        }
        if (count != null && count >= props.getMaxFailures()) {
            // 锁定维度 = failKey 去掉计数前缀（user:{name} / ip:{ip}）
            String dimension = failKey.substring(FAIL_PREFIX.length());
            redis.opsForValue().set(LOCK_PREFIX + dimension, "1", Duration.ofSeconds(props.getLockSeconds()));
            redis.delete(failKey);
        }
    }
}
