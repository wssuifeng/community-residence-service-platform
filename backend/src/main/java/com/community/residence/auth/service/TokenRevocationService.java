package com.community.residence.auth.service;

import com.community.residence.auth.entity.AuthTokenBlacklist;
import com.community.residence.auth.mapper.AuthTokenBlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 用户级令牌吊销（接口设计.md 9.10.1.7/9.10.1.8/9.10.2 业务规则）：
 * 冻结账号、修改密码、绑定社区变更后，该用户全部存量令牌失效。
 * JWT 无状态无法枚举 jti，改为「吊销时间戳」语义——签发时间早于吊销时间的令牌拒绝。
 * DB（auth_token_blacklist 虚拟记录，reason=PERMISSION_CHANGE）为权威来源，
 * Redis 缓存吊销时间戳；比较基准为令牌 iat。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String KEY_PREFIX = "user:revoked:";
    /** 吊销记录保留时长：与令牌最长生命周期一致（2h 后存量令牌自然过期） */
    private static final Duration REVOKE_RETENTION = Duration.ofHours(2);

    private final AuthTokenBlacklistMapper blacklistMapper;
    private final StringRedisTemplate redisTemplate;

    /** 吊销用户全部存量令牌（冻结/改密/权限变更） */
    @Transactional(rollbackFor = Exception.class)
    public void revokeUser(Long userId) {
        AuthTokenBlacklist record = new AuthTokenBlacklist();
        // 虚拟 jti：不与真实令牌 jti 冲突，记录仅作 DB 权威吊销时间戳载体
        record.setJti("USER-" + userId + "-" + System.currentTimeMillis());
        record.setUserId(userId);
        record.setExpireTime(LocalDateTime.now().plus(REVOKE_RETENTION));
        record.setReason("PERMISSION_CHANGE");
        blacklistMapper.insert(record);

        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId,
                    String.valueOf(System.currentTimeMillis() / 1000), REVOKE_RETENTION);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，用户吊销已写 DB，缓存未生效：userId={}", userId);
        }
    }

    /** 校验令牌是否被用户级吊销（签发时间早于吊销时间即失效） */
    public boolean isUserRevoked(Long userId, Date issuedAt) {
        Long revokedAt = readRevokedAt(userId);
        return revokedAt != null && issuedAt.getTime() / 1000 < revokedAt;
    }

    private Long readRevokedAt(Long userId) {
        try {
            String cached = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (RedisConnectionFailureException e) {
            // 降级查 DB
        }
        LocalDateTime revokedTime = blacklistMapper.selectLatestRevocationTime(userId);
        if (revokedTime == null) {
            return null;
        }
        long epochSec = revokedTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        try {
            long ttl = Duration.between(LocalDateTime.now(),
                    revokedTime.plus(REVOKE_RETENTION)).toMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(KEY_PREFIX + userId,
                        String.valueOf(epochSec), Duration.ofMillis(ttl));
            }
        } catch (RedisConnectionFailureException ignored) {
            // 回种失败不影响本次结果
        }
        return epochSec;
    }
}
