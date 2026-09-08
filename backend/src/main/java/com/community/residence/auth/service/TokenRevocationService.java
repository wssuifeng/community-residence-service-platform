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
 * 冻结账号、修改密码、角色变更、绑定社区变更后，该用户全部存量令牌失效。
 * JWT 无状态无法枚举 jti，改为「吊销时间戳」语义——签发时间早于吊销时间的令牌拒绝。
 * resident 与 sys_user 两套账号的 userId 空间重叠，吊销记录必须区分账号体系
 * （scope 前缀：R- 居民 / A- 后台账号），否则冻结居民会误伤同 ID 的后台账号。
 * DB（auth_token_blacklist 虚拟记录）为权威来源，Redis 缓存吊销时间戳。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    /** 吊销 scope：居民账号体系 */
    public static final String SCOPE_RESIDENT = "R";
    /** 吊销 scope：后台账号体系（SUPER_ADMIN/ADMIN/STAFF） */
    public static final String SCOPE_ADMIN = "A";

    private static final String KEY_PREFIX = "user:revoked:";
    /** 吊销记录保留时长：与令牌最长生命周期一致（2h 后存量令牌自然过期） */
    private static final Duration REVOKE_RETENTION = Duration.ofHours(2);

    private final AuthTokenBlacklistMapper blacklistMapper;
    private final StringRedisTemplate redisTemplate;

    /** 吊销居民全部存量令牌（居民冻结/改密） */
    @Transactional(rollbackFor = Exception.class)
    public void revokeResident(Long residentId) {
        revoke(SCOPE_RESIDENT, residentId);
    }

    /** 吊销后台账号全部存量令牌（sys_user 冻结/改密/角色与绑定变更） */
    @Transactional(rollbackFor = Exception.class)
    public void revokeAdminUser(Long userId) {
        revoke(SCOPE_ADMIN, userId);
    }

    private void revoke(String scope, Long userId) {
        AuthTokenBlacklist record = new AuthTokenBlacklist();
        // 虚拟 jti：scope 前缀隔离两套账号体系，避免 userId 重叠互相误伤
        record.setJti("USER-" + scope + "-" + userId + "-" + System.currentTimeMillis());
        record.setUserId(userId);
        record.setExpireTime(LocalDateTime.now().plus(REVOKE_RETENTION));
        record.setReason("PERMISSION_CHANGE");
        blacklistMapper.insert(record);

        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + scope + "-" + userId,
                    String.valueOf(System.currentTimeMillis() / 1000), REVOKE_RETENTION);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，用户吊销已写 DB，缓存未生效：scope={}, userId={}", scope, userId);
        }
    }

    /** 校验居民令牌是否被用户级吊销（签发时间早于吊销时间即失效） */
    public boolean isResidentRevoked(Long residentId, Date issuedAt) {
        return isRevoked(SCOPE_RESIDENT, residentId, issuedAt);
    }

    /** 校验后台账号令牌是否被用户级吊销 */
    public boolean isAdminUserRevoked(Long userId, Date issuedAt) {
        return isRevoked(SCOPE_ADMIN, userId, issuedAt);
    }

    private boolean isRevoked(String scope, Long userId, Date issuedAt) {
        Long revokedAt = readRevokedAt(scope, userId);
        return revokedAt != null && issuedAt.getTime() / 1000 < revokedAt;
    }

    private Long readRevokedAt(String scope, Long userId) {
        String key = KEY_PREFIX + scope + "-" + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (RedisConnectionFailureException e) {
            // 降级查 DB
        }
        String scopePrefix = "USER-" + scope + "-" + userId + "-%";
        LocalDateTime revokedTime = blacklistMapper.selectLatestRevocationTime(scopePrefix);
        if (revokedTime == null) {
            return null;
        }
        long epochSec = revokedTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        try {
            long ttl = Duration.between(LocalDateTime.now(),
                    revokedTime.plus(REVOKE_RETENTION)).toMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(key, String.valueOf(epochSec), Duration.ofMillis(ttl));
            }
        } catch (RedisConnectionFailureException ignored) {
            // 回种失败不影响本次结果
        }
        return epochSec;
    }
}
