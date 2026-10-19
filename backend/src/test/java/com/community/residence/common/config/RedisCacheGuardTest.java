package com.community.residence.common.config;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RedisCacheGuardTest {
    @Test void repeatedOutageSkipsConnectionAndRecoveryRetriesAfterCooldown() {
        var time = new AtomicLong(1); var calls = new AtomicInteger();
        var guard = new RedisCacheGuard(time::get, Duration.ofSeconds(2));
        assertThatThrownBy(() -> guard.execute(() -> { calls.incrementAndGet(); throw new RedisConnectionFailureException("offline"); })).isInstanceOf(RedisConnectionFailureException.class);
        for (int i = 0; i < 100; i++) assertThatThrownBy(() -> guard.execute(calls::incrementAndGet)).isInstanceOf(RedisConnectionFailureException.class);
        assertThat(calls.get()).isEqualTo(1);
        time.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(guard.execute(calls::incrementAndGet)).isEqualTo(2);
        assertThat(guard.execute(calls::incrementAndGet)).isEqualTo(3);
    }
    @Test void commandErrorsAreNeitherHiddenNorOpenCircuit() {
        var guard = new RedisCacheGuard();
        assertThatThrownBy(() -> guard.execute(() -> { throw new InvalidDataAccessApiUsageException("WRONGTYPE"); })).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(guard.execute(() -> 7)).isEqualTo(7);
    }
    @Test void halfOpenAllowsOnlyOneProbeAndDoesNotBlockOtherThreads() throws Exception {
        var time = new AtomicLong(1); var guard = new RedisCacheGuard(time::get, Duration.ofNanos(1));
        assertThatThrownBy(() -> guard.execute(() -> { throw new RedisConnectionFailureException("offline"); })).isInstanceOf(RedisConnectionFailureException.class);
        time.incrementAndGet(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> guard.execute(() -> { entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); } return 7; }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> guard.execute(() -> 9)).isInstanceOf(RedisConnectionFailureException.class);
            release.countDown(); assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(7);
            assertThat(guard.execute(() -> 9)).isEqualTo(9);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void templateSharesOutageAcrossGetExistsAndLua() {
        var factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("offline"));
        var template = new FailFastStringRedisTemplate(factory);
        assertThatThrownBy(() -> template.opsForValue().get("config:x")).isInstanceOf(RedisConnectionFailureException.class);
        assertThatThrownBy(() -> template.hasKey("token:blacklist:x")).isInstanceOf(RedisConnectionFailureException.class);
        assertThatThrownBy(() -> template.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("return 1", Long.class), java.util.List.of())).isInstanceOf(RedisConnectionFailureException.class);
        verify(factory, times(1)).getConnection();
    }
    @Test void timeoutsAreBoundedForActualRedissonSingleServer() {
        var config = new org.redisson.config.Config(); config.useSingleServer().setAddress("redis://127.0.0.1:6387");
        new RedisConfig().redisTimeoutCustomizer().customize(config);
        assertThat(config.useSingleServer().getConnectTimeout()).isEqualTo(1000);
        assertThat(config.useSingleServer().getTimeout()).isEqualTo(1000);
        assertThat(config.useSingleServer().getRetryAttempts()).isZero();
    }
}
