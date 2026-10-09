package com.example.backend.ratelimit;

import com.example.backend.config.AdminProperties;
import com.example.backend.observability.LlmUsageRecordRepository;
import com.example.backend.user.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitService} 单元测试：三道闸门判定、管理员豁免、fail-open、名额释放。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private LlmUsageRecordRepository usageRepository;

    private RateLimitProperties props;
    private AdminProperties adminProperties;
    private RateLimitService service;

    private UserEntity alice;

    /** 两个 Lua 计数键的脚本返回（1=获名额，0=超额），每个用例自行设置 */
    private long rpmResult = 1L;
    private long activeResult = 1L;

    @BeforeEach
    void setUp() {
        props = new RateLimitProperties();
        props.setRequestsPerMinute(20);
        props.setMaxConcurrent(4);
        props.setDailyTokenBudget(1000L);
        props.setConcurrentTtlSeconds(360L);

        adminProperties = new AdminProperties();
        service = new RateLimitService(props, adminProperties, redis, usageRepository);

        alice = new UserEntity();
        alice.setUsername("alice");
    }

    /** 按计数键（rpm/active）分发脚本返回值 */
    @SuppressWarnings("unchecked")
    private void stubRedisSlots() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<String> keys = inv.getArgument(1);
                    String key = keys.get(0);
                    if (key.contains(":rpm:")) {
                        return rpmResult;
                    }
                    if (key.contains(":active:")) {
                        return activeResult;
                    }
                    return 1L;
                });
    }

    @Test
    void disabled_allowsEverythingWithoutCallingRedisOrDb() {
        props.setEnabled(false);

        RateLimitService.Decision decision = service.tryAcquire(alice);

        assertThat(decision.allowed()).isTrue();
        verifyNoInteractions(redis, usageRepository);
    }

    @Test
    void allGatesZeroed_allowsWithoutCallingRedisOrDb() {
        props.setRequestsPerMinute(0);
        props.setMaxConcurrent(0);
        props.setDailyTokenBudget(0);

        assertThat(service.tryAcquire(alice).allowed()).isTrue();
        verifyNoInteractions(redis, usageRepository);
    }

    @Test
    void admin_isExemptFromAllGates() {
        adminProperties.setUsernames(List.of("alice"));
        // 即使阈值极低，豁免路径下 Redis 与用量库都不应被触碰
        props.setRequestsPerMinute(1);
        props.setMaxConcurrent(1);
        props.setDailyTokenBudget(1);

        assertThat(service.tryAcquire(alice).allowed()).isTrue();
        verifyNoInteractions(redis, usageRepository);
    }

    @Test
    void rpmExceeded_rejectsWithRateLimitedAndSkipsLaterGates() {
        rpmResult = 0L;
        stubRedisSlots();

        RateLimitService.Decision decision = service.tryAcquire(alice);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("RATE_LIMITED");
        assertThat(decision.message()).contains("每分钟最多 20 条");
        // 频率闸门先拒绝：不查预算、不占在途名额
        verifyNoInteractions(usageRepository);
        verify(redis, never()).execute(any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.<List<String>>argThat(k -> k.get(0).contains(":active:")),
                any(), any());
    }

    @Test
    void dailyBudgetExceeded_rejectsAndDoesNotAcquireConcurrentSlot() {
        stubRedisSlots();
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenReturn(1000L); // 含等于：已达上限即拒

        RateLimitService.Decision decision = service.tryAcquire(alice);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("DAILY_BUDGET_EXCEEDED");
        assertThat(decision.message()).contains("1000");
        verify(redis, never()).execute(any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.<List<String>>argThat(k -> k.get(0).contains(":active:")),
                any(), any());
    }

    @Test
    void concurrentSlotsExhausted_rejectsWithTooManyConcurrent() {
        activeResult = 0L;
        stubRedisSlots();
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenReturn(100L);

        RateLimitService.Decision decision = service.tryAcquire(alice);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("TOO_MANY_CONCURRENT");
        assertThat(decision.message()).contains("最多 4 个");
    }

    @Test
    void allGatesPass_allowsAndUsesPerMinuteWindowOf60Seconds() {
        stubRedisSlots();
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenReturn(null); // null 合计按 0 处理

        assertThat(service.tryAcquire(alice).allowed()).isTrue();

        // 仅校验频率键那次调用（并发键 TTL 不同，混在一起 verify 会命中两次）
        ArgumentCaptor<String> ttl = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.<List<String>>argThat(
                        k -> k.get(0).equals("rl:rpm:alice")),
                ttl.capture(), any());
        assertThat(ttl.getValue()).isEqualTo("60");
    }

    @Test
    void redisOutage_failsOpen() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenReturn(10L);

        // Redis 全挂时频率/并发闸门 fail-open，预算查询独立放行
        assertThat(service.tryAcquire(alice).allowed()).isTrue();
    }

    @Test
    void budgetQueryFailure_failsOpen() {
        stubRedisSlots();
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db timeout"));

        assertThat(service.tryAcquire(alice).allowed()).isTrue();
    }

    @Test
    void release_decrementsActiveSlot() {
        stubRedisSlots();
        when(usageRepository.sumTotalTokensSince(eq("alice"), any(LocalDateTime.class)))
                .thenReturn(0L);
        service.tryAcquire(alice);

        service.release("alice");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keys.capture());
        assertThat(keys.getValue().get(0)).isEqualTo("rl:active:alice");
    }

    @Test
    void release_whenDisabled_doesNothing() {
        props.setEnabled(false);

        service.release("alice");

        verifyNoInteractions(redis);
    }

    @Test
    void release_admin_doesNothing() {
        adminProperties.setUsernames(List.of("alice"));

        service.release("alice");

        verifyNoInteractions(redis);
    }
}
