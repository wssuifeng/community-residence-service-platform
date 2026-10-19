package com.community.residence.common.config;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 可降级缓存的故障等待保护；短暂跳过不可用Redis，恢复时只放行一个探测请求。 */
public final class RedisCacheGuard {
    private final AtomicLong retryAt = new AtomicLong();
    private final AtomicBoolean probing = new AtomicBoolean();
    private final LongSupplier clock;
    private final long cooldown;

    public RedisCacheGuard() { this(System::nanoTime, Duration.ofSeconds(2)); }
    RedisCacheGuard(LongSupplier clock, Duration cooldown) {
        this.clock = clock;
        this.cooldown = cooldown.toNanos();
    }

    /* 正常请求不串行；传输失败后立即走既有DB/轮询降级，不吞命令及编解码错误。
       在途成功不能覆盖另一请求刚发现的故障，半开探测同样以CAS核对故障代次。 */
    public <T> T execute(Supplier<T> call) {
        long observed = retryAt.get();
        boolean probe = observed != 0;
        if (probe && (clock.getAsLong() < observed || !probing.compareAndSet(false, true))) {
            throw new RedisConnectionFailureException("Redis故障窗口内跳过缓存访问");
        }
        try {
            T result = call.get();
            if (probe) retryAt.compareAndSet(observed, 0);
            return result;
        } catch (DataAccessException failure) {
            RedisFailures.rethrowUnlessUnavailable(failure);
            retryAt.set(clock.getAsLong() + cooldown);
            throw failure;
        } finally {
            if (probe) probing.set(false);
        }
    }
}
