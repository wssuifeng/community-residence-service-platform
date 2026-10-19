package com.community.residence.common.auth.service;

import com.community.residence.common.auth.entity.AuthTokenBlacklist;
import com.community.residence.common.auth.mapper.AuthTokenBlacklistMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.ConnectException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenBlacklistRedisOutageTest {
    @Test
    void validTokenUsesDatabaseDuringWrappedDriverFailure() {
        var mapper = mock(AuthTokenBlacklistMapper.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("token:blacklist:valid")).thenThrow(new InvalidDataAccessApiUsageException("driver", new ConnectException("offline")));
        var service = new TokenBlacklistService(mapper, redis);
        assertThat(service.isBlacklisted("valid")).isFalse();
        verify(mapper).selectByJtiNotExpired("valid");
    }
    @Test
    void revokedTokenRemainsRejectedDuringWrappedDriverFailure() {
        var mapper = mock(AuthTokenBlacklistMapper.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("token:blacklist:revoked")).thenThrow(new InvalidDataAccessApiUsageException("driver", new ConnectException("offline")));
        when(mapper.selectByJtiNotExpired("revoked")).thenReturn(new AuthTokenBlacklist());
        assertThat(new TokenBlacklistService(mapper, redis).isBlacklisted("revoked")).isTrue();
    }
    @Test
    void unavailableDatabaseIsNeverTreatedAsValidAuthentication() {
        var mapper = mock(AuthTokenBlacklistMapper.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("token:blacklist:unknown")).thenThrow(new InvalidDataAccessApiUsageException("driver", new ConnectException("offline")));
        when(mapper.selectByJtiNotExpired("unknown")).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> new TokenBlacklistService(mapper, redis).isBlacklisted("unknown")).isInstanceOf(IllegalStateException.class);
    }
}
