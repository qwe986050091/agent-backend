package com.example.backend.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * 会话级「始终允许此工具」授权存储（Redis Set）。
 *
 * 键：tool:grant:{userId}:{conversationId} → Set&lt;toolName&gt;，TTL 7 天。
 * 用户在审批弹窗勾选「本次会话始终允许」且批准后写入；
 * ChatController 每回合读取并随 payload 下发给 agent（agent 保持无状态）。
 */
@Component
public class ToolGrantStore {

    private static final Logger log = LoggerFactory.getLogger(ToolGrantStore.class);

    private static final String KEY_PREFIX = "tool:grant:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public ToolGrantStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String userId, Long conversationId) {
        return KEY_PREFIX + userId + ":" + (conversationId == null ? 0 : conversationId);
    }

    /** 授予某会话对某工具的免确认权（TTL 7 天） */
    public void grant(String userId, Long conversationId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        String key = key(userId, conversationId);
        redis.opsForSet().add(key, toolName);
        redis.expire(key, TTL);
        log.info("[ToolGrant] 会话工具授权: user={}, conversation={}, tool={}", userId, conversationId, toolName);
    }

    /** 读取某会话已授权工具（读时滑动续期）；无授权返回空集 */
    public Set<String> list(String userId, Long conversationId) {
        String key = key(userId, conversationId);
        Set<String> members = redis.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        redis.expire(key, TTL);
        return members;
    }
}
