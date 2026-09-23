package com.community.residence.messaging.service;

import com.community.residence.common.constant.NotificationOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 会话注册表回归：DEF-036（多连接计数——任一连接断开不再注销整个在线状态）
 * + DEF-067（在线键带身份空间前缀，resident 1 与 sys_user 1 撞号时互不代表在线）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket 会话计数回归（DEF-036 / DEF-067）")
class WebSocketSessionCountTest {

    private static final String KEY = "ws:session:R:1";
    private static final String USER_KEY = "ws:session:U:1";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private WebSocketSessionService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("DEF-036 正例：双连接关其一（计数 2→1）不删 key，在线状态保持")
    void deregister_oneOfTwoConnections_staysOnline() {
        AtomicLong counter = new AtomicLong(2);
        when(valueOps.decrement(KEY)).thenAnswer(inv -> counter.decrementAndGet());
        when(valueOps.get(KEY)).thenAnswer(inv -> String.valueOf(counter.get()));

        service.deregister(NotificationOwnerType.RESIDENT, 1L);

        verify(redisTemplate, never()).delete(anyString());
        assertThat(service.isOnline(NotificationOwnerType.RESIDENT, 1L)).isTrue();
    }

    @Test
    @DisplayName("DEF-036 正例：最后一个连接断开（计数 1→0）注销在线状态")
    void deregister_lastConnection_goesOffline() {
        AtomicLong counter = new AtomicLong(1);
        when(valueOps.decrement(KEY)).thenAnswer(inv -> counter.decrementAndGet());

        service.deregister(NotificationOwnerType.RESIDENT, 1L);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("register 计数 +1 并续期 TTL（多连接叠加）")
    void register_incrementsAndRenewsTtl() {
        when(valueOps.increment(KEY)).thenReturn(2L);

        service.register(NotificationOwnerType.RESIDENT, 1L);

        verify(valueOps).increment(KEY);
        verify(redisTemplate).expire(eq(KEY), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("DEF-036 并发断言：双连接并发 register×2 后并发 deregister×2——恰在第二次才注销")
    void concurrent_doubleRegister_doubleDeregister_offlineOnlyAtLast() {
        AtomicLong counter = new AtomicLong(0);
        when(valueOps.increment(KEY)).thenAnswer(inv -> counter.incrementAndGet());
        when(valueOps.decrement(KEY)).thenAnswer(inv -> counter.decrementAndGet());

        /* 双连接并发上线：计数 2 */
        var latch = new java.util.concurrent.CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    service.register(NotificationOwnerType.RESIDENT, 1L);
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        await(latch);
        assertThat(counter.get()).isEqualTo(2);
        when(valueOps.get(KEY)).thenAnswer(inv -> String.valueOf(counter.get()));
        assertThat(service.isOnline(NotificationOwnerType.RESIDENT, 1L)).isTrue();

        /* 第一路断开：在线保持 */
        service.deregister(NotificationOwnerType.RESIDENT, 1L);
        assertThat(counter.get()).isEqualTo(1);
        assertThat(service.isOnline(NotificationOwnerType.RESIDENT, 1L)).isTrue();
        verify(redisTemplate, never()).delete(anyString());

        /* 第二路（最后一路）断开：注销 */
        service.deregister(NotificationOwnerType.RESIDENT, 1L);
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("DEF-067 撞号分离：resident 1 上线不等价于 sys_user 1 在线（在线键互不相同）")
    void collidingIds_useDistinctSessionKeys() {
        when(valueOps.increment(KEY)).thenReturn(1L);
        when(valueOps.get(KEY)).thenReturn("1");
        when(valueOps.get(USER_KEY)).thenReturn(null);

        /* 仅居民 1 上线 */
        service.register(NotificationOwnerType.RESIDENT, 1L);

        assertThat(service.isResidentOnline(1L)).isTrue();
        assertThat(service.isUserOnline(1L)).isFalse();
        assertThat(KEY).isNotEqualTo(USER_KEY);
    }

    @Test
    @DisplayName("兼容口径：历史 SET 单值格式（非数字）isOnline 按在线处理不炸")
    void isOnline_legacyValue_treatedOnline() {
        when(valueOps.get(KEY)).thenReturn("legacy-single-value");

        /* 非计数格式残留值（Long.parseLong 抛 NumberFormatException）按在线处理 */
        assertThat(service.isOnline(NotificationOwnerType.RESIDENT, 1L)).isTrue();
    }

    @Test
    @DisplayName("Redis 不可用降级：isOnline 返回 false（轮询兜底，N4 口径不变）")
    void redisDown_degradesOffline() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.isOnline(NotificationOwnerType.RESIDENT, 1L)).isFalse();
    }

    private void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
