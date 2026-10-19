package com.community.residence.common.config;

import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import static org.assertj.core.api.Assertions.*;

class RedisFailuresTest {
    @Test
    void redissonWrappedConnectionRefusedMayFallBack() {
        var failure = new InvalidDataAccessApiUsageException("driver wrapper",
                new RedisException("command failed", new ConnectException("connection refused")));
        assertThatCode(() -> RedisFailures.rethrowUnlessUnavailable(failure)).doesNotThrowAnyException();
    }
    @Test
    void wrappedTransportTimeoutMayFallBack() {
        var failure = new InvalidDataAccessApiUsageException("driver wrapper", new SocketTimeoutException("timed out"));
        assertThatCode(() -> RedisFailures.rethrowUnlessUnavailable(failure)).doesNotThrowAnyException();
    }
    @Test
    void originalSpringConnectionFailureStillFallsBack() {
        var failure = new RedisConnectionFailureException("offline");
        assertThatCode(() -> RedisFailures.rethrowUnlessUnavailable(failure)).doesNotThrowAnyException();
    }
    @Test
    void malformedCommandIsNotAnAvailabilityFailure() {
        var failure = new InvalidDataAccessApiUsageException("wrong command", new RedisException("WRONGTYPE"));
        assertThatThrownBy(() -> RedisFailures.rethrowUnlessUnavailable(failure)).isSameAs(failure);
    }
    @Test
    void serializationFailureIsNotSwallowed() {
        var failure = new InvalidDataAccessApiUsageException("codec failed", new IllegalArgumentException("invalid data"));
        assertThatThrownBy(() -> RedisFailures.rethrowUnlessUnavailable(failure)).isSameAs(failure);
    }
}
