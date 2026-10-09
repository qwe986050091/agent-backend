package com.example.backend.ratelimit;

import com.example.backend.config.AdminProperties;
import com.example.backend.observability.LlmUsageRecordRepository;
import com.example.backend.user.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户级限流与配额（P0-4），防死循环 prompt / 被盗会话无上限烧 token。
 *
 * 三道闸门（顺序：频率 → 日预算 → 在途并发）：
 * <ol>
 *   <li>每分钟请求数：Redis 固定窗口计数（INCR + 首包 EXPIRE，Lua 原子超量回退）</li>
 *   <li>每日 token 预算：查 llm_usage_record 当日 total_tokens 聚合，超额拒绝</li>
 *   <li>同时在途对话数：Redis 计数，{@link #release(String)} 在回合结束时释放；
 *       兜底 TTL 防崩溃残留（见 {@link RateLimitProperties#getConcurrentTtlSeconds()}）</li>
 * </ol>
 *
 * 设计约定：
 * - 管理员白名单可豁免（exempt-admins）；
 * - 被频率/预算闸门拒绝不占用在途名额；频率窗口按惯例计数（被拒请求也占窗口名额）；
 * - Redis/MySQL 故障时 <b>fail-open</b>（记日志放行）：限流是护栏不是生死线，
 *   Redis 整体不可用时登录会话本身也已失效，此处无需再双重拒绝。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private static final String RPM_KEY_PREFIX = "rl:rpm:";
    private static final String ACTIVE_KEY_PREFIX = "rl:active:";

    /**
     * 原子「计数并判超」：INCR；首次计数设 TTL；超过上限则回退 DECR 并返回 0。
     * KEYS[1]=计数键  ARGV[1]=TTL 秒  ARGV[2]=上限
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT;
    /** 原子释放：DECR，负数兜底归零（清理过期残留/异常重入）。KEYS[1]=计数键 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                  redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                if current > tonumber(ARGV[2]) then
                  redis.call('DECR', KEYS[1])
                  return 0
                end
                return 1
                """, Long.class);
        RELEASE_SCRIPT = new DefaultRedisScript<>("""
                local current = redis.call('DECR', KEYS[1])
                if current < 0 then
                  redis.call('SET', KEYS[1], '0')
                  return 0
                end
                return current
                """, Long.class);
    }

    private final RateLimitProperties props;
    private final AdminProperties adminProperties;
    private final StringRedisTemplate redis;
    private final LlmUsageRecordRepository usageRepository;

    public RateLimitService(RateLimitProperties props,
                            AdminProperties adminProperties,
                            StringRedisTemplate redis,
                            LlmUsageRecordRepository usageRepository) {
        this.props = props;
        this.adminProperties = adminProperties;
        this.redis = redis;
        this.usageRepository = usageRepository;
    }

    /** 放行决策；allowed=true 时调用方必须在回合结束后调用 {@link #release(String)} */
    public record Decision(boolean allowed, String code, String message) {
        static Decision allow() {
            return new Decision(true, null, null);
        }
        static Decision reject(String code, String message) {
            return new Decision(false, code, message);
        }
    }

    /** 回合开始前尝试获取名额（覆盖同步与流式两条对话入口） */
    public Decision tryAcquire(UserEntity user) {
        if (!props.isEnabled() || user == null) {
            return Decision.allow();
        }
        String username = user.getUsername();
        if (props.isExemptAdmins() && adminProperties.isAdmin(username)) {
            return Decision.allow();
        }

        // 1. 每分钟请求频率
        if (props.getRequestsPerMinute() > 0 && !acquireSlot(
                RPM_KEY_PREFIX + username, 60, props.getRequestsPerMinute())) {
            return Decision.reject("RATE_LIMITED",
                    "消息发送过于频繁，请稍后再试（每分钟最多 " + props.getRequestsPerMinute() + " 条消息）");
        }

        // 2. 当日 token 预算（createdAt 与聚合均按服务器本地时区，口径一致）
        if (props.getDailyTokenBudget() > 0) {
            try {
                Long usedToday = usageRepository.sumTotalTokensSince(username, LocalDate.now().atStartOfDay());
                long used = usedToday == null ? 0L : usedToday;
                if (used >= props.getDailyTokenBudget()) {
                    return Decision.reject("DAILY_BUDGET_EXCEEDED",
                            "今日 token 用量已达每日上限（" + props.getDailyTokenBudget()
                                    + " tokens），请明天再试或联系管理员调整额度");
                }
            } catch (Exception e) {
                // 预算查询失败 fail-open：不能因为用量表抖动让用户完全无法对话
                log.warn("[RateLimit] 用户 {} 当日预算查询失败，放行: {}", username, e.getMessage());
            }
        }

        // 3. 同时在途对话数（放最后：只有真正放行时才占名额）
        if (props.getMaxConcurrent() > 0 && !acquireSlot(
                ACTIVE_KEY_PREFIX + username, props.getConcurrentTtlSeconds(), props.getMaxConcurrent())) {
            return Decision.reject("TOO_MANY_CONCURRENT",
                    "同时进行的对话过多，请等待当前回复结束后再发送（最多 " + props.getMaxConcurrent() + " 个同时进行）");
        }

        return Decision.allow();
    }

    /**
     * 回合结束释放在途名额（同步路径在 afterCompletion；SSE 在异步分发完成后的 afterCompletion）。
     * 幂等：重复/多余调用由 Lua 脚本归零兜底；频率窗口与日预算无需释放。
     */
    public void release(String username) {
        if (!props.isEnabled() || username == null || props.getMaxConcurrent() <= 0) {
            return;
        }
        if (props.isExemptAdmins() && adminProperties.isAdmin(username)) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(ACTIVE_KEY_PREFIX + username));
        } catch (Exception e) {
            log.warn("[RateLimit] 用户 {} 在途名额释放失败（依赖 {}s TTL 兜底）: {}",
                    username, props.getConcurrentTtlSeconds(), e.getMessage());
        }
    }

    /** 执行原子计数脚本；Redis 异常 fail-open（返回 true 放行） */
    private boolean acquireSlot(String key, long ttlSeconds, int max) {
        try {
            Long acquired = redis.execute(ACQUIRE_SCRIPT, List.of(key),
                    String.valueOf(ttlSeconds), String.valueOf(max));
            return acquired == null || acquired == 1L;
        } catch (Exception e) {
            log.warn("[RateLimit] 限流计数失败（key={}），fail-open 放行: {}", key, e.getMessage());
            return true;
        }
    }
}
