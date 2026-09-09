package com.example.backend.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis 登录会话存储（替代原 app_user.token 列）
 *
 * 键设计：
 *   auth:session:{token} → userId     登录令牌 → 用户（TTL 过期）
 *   auth:user:{userId}   → 当前活跃 token（单端互踢：新登录顶掉旧会话）
 *
 * 会话每次被校验都会滑动续期（TTL 重置），用户持续活跃则长期有效；
 * 停止使用 ttl 时间后自动过期，前端收到 401 自动跳回登录页。
 */
@Component
public class SessionStore {

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String ACTIVE_PREFIX = "auth:user:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionStore(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(props.getSessionTtlSeconds());
    }

    /** 为用户创建新会话（单端互踢：清除该用户旧会话），返回 token */
    public String createSession(Long userId) {
        String activeKey = ACTIVE_PREFIX + userId;
        String oldToken = redis.opsForValue().get(activeKey);
        if (oldToken != null && !oldToken.isEmpty()) {
            redis.delete(SESSION_PREFIX + oldToken);
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(SESSION_PREFIX + token, String.valueOf(userId), ttl);
        redis.opsForValue().set(activeKey, token, ttl);
        return token;
    }

    /** 校验 token：有效则滑动续期并返回 userId，无效/过期返回 null */
    public Long validate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String sessionKey = SESSION_PREFIX + token;
        String userId = redis.opsForValue().get(sessionKey);
        if (userId == null) {
            return null;
        }
        // 滑动续期：会话与活跃标记一起延长
        redis.expire(sessionKey, ttl);
        redis.expire(ACTIVE_PREFIX + userId, ttl);
        return Long.valueOf(userId);
    }

    /** 登出：删除会话；若为该用户当前活跃会话，同时清除活跃标记 */
    public void revoke(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String sessionKey = SESSION_PREFIX + token;
        String userId = redis.opsForValue().get(sessionKey);
        if (userId != null) {
            String activeKey = ACTIVE_PREFIX + userId;
            String active = redis.opsForValue().get(activeKey);
            if (token.equals(active)) {
                redis.delete(activeKey);
            }
        }
        redis.delete(sessionKey);
    }
}
