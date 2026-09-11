package com.community.residence.auth.service;

import com.community.residence.auth.mapper.AuthTokenBlacklistMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DEF-009 修复回归：冻结/权限变更与登录同秒时（iat==revokedAt），
 * 存量令牌必须判定为已吊销（接口设计 9.10.1.x「吊销该用户全部存量令牌」口径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("令牌吊销同秒碰撞修复回归（DEF-009）")
class TokenRevocationFixTest {

    private static final long REVOKED_AT = 1_800_000_000L;

    @Mock
    private AuthTokenBlacklistMapper blacklistMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenRevocationService tokenRevocationService;

    @Test
    @DisplayName("同秒签发（iat==revokedAt）→ 已吊销（原判定放行，冻结后令牌继续有效）")
    void sameSecondIssued_revoked() {
        stubRedisRevokedAt(REVOKED_AT);
        assertThat(tokenRevocationService.isAdminUserRevoked(1L, new Date(REVOKED_AT * 1000)))
                .as("同秒签发的令牌必须立即失效").isTrue();
    }

    @Test
    @DisplayName("早于吊销时间签发 → 已吊销（原有正确行为保持）")
    void beforeRevokedAt_revoked() {
        stubRedisRevokedAt(REVOKED_AT);
        assertThat(tokenRevocationService.isResidentRevoked(1L, new Date((REVOKED_AT - 10) * 1000)))
                .isTrue();
    }

    @Test
    @DisplayName("晚于吊销时间签发（吊销后重新登录）→ 有效")
    void afterRevokedAt_valid() {
        stubRedisRevokedAt(REVOKED_AT);
        assertThat(tokenRevocationService.isResidentRevoked(1L, new Date((REVOKED_AT + 10) * 1000)))
                .isFalse();
    }

    @Test
    @DisplayName("无吊销记录 → 有效")
    void noRevocation_valid() {
        stubRedisRevokedAt(null);
        assertThat(tokenRevocationService.isAdminUserRevoked(1L, new Date(REVOKED_AT * 1000)))
                .isFalse();
    }

    @Test
    @DisplayName("Redis 不可用降级 DB：同秒签发同样已吊销")
    void redisDown_dbFallback_sameSecondRevoked() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        lenient().when(blacklistMapper.selectLatestRevocationTime(anyString()))
                .thenReturn(java.time.LocalDateTime.of(2027, 1, 15, 8, 0));
        long epochSec = java.time.LocalDateTime.of(2027, 1, 15, 8, 0)
                .atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        assertThat(tokenRevocationService.isAdminUserRevoked(1L, new Date(epochSec * 1000)))
                .as("DB 降级路径同口径").isTrue();
    }

    private void stubRedisRevokedAt(Long revokedAt) {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString()))
                .thenReturn(revokedAt == null ? null : String.valueOf(revokedAt));
    }
}
