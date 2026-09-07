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

/**
 * 令牌黑名单服务（架构设计.md §3.1 黑名单双写）：
 * DB（auth_token_blacklist）为权威来源，Redis 为性能缓存；
 * Redis 不可用时降级查 DB，登出仍可成功、黑名单仍可生效（N4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "token:blacklist:";

    private final AuthTokenBlacklistMapper blacklistMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 拉黑令牌：DB 写入（权威，失败回滚）+ Redis 缓存（失败不影响结果）。
     *
     * @param jti        令牌唯一标识
     * @param userId     用户 ID
     * @param expireTime 令牌原过期时间（黑名单记录保留至该时刻）
     * @param reason     LOGOUT / PERMISSION_CHANGE
     */
    @Transactional(rollbackFor = Exception.class)
    public void blacklist(String jti, Long userId, LocalDateTime expireTime, String reason) {
        AuthTokenBlacklist record = new AuthTokenBlacklist();
        record.setJti(jti);
        record.setUserId(userId);
        record.setExpireTime(expireTime);
        record.setReason(reason);
        blacklistMapper.insert(record);

        try {
            long ttl = Duration.between(LocalDateTime.now(), expireTime).toMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttl));
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，黑名单已写入 DB，登出成功但缓存未生效：jti={}", jti);
        }
    }

    /** 按用户批量拉黑全部有效令牌（权限变更场景，调用方负责查明各 jti/过期时间） */
    @Transactional(rollbackFor = Exception.class)
    public void blacklistAll(String reason, TokenInfo... tokens) {
        for (TokenInfo info : tokens) {
            blacklist(info.jti(), info.userId(), info.expireTime(), reason);
        }
    }

    /**
     * 黑名单校验：优先 Redis，未命中查 DB 并回种；Redis 不可用降级查 DB。
     */
    public boolean isBlacklisted(String jti) {
        try {
            Boolean cached = redisTemplate.hasKey(KEY_PREFIX + jti);
            if (Boolean.TRUE.equals(cached)) {
                return true;
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，降级查 DB 黑名单：jti={}", jti);
            return blacklistMapper.selectByJtiNotExpired(jti) != null;
        }

        AuthTokenBlacklist record = blacklistMapper.selectByJtiNotExpired(jti);
        if (record == null) {
            return false;
        }
        try {
            long ttl = Duration.between(LocalDateTime.now(), record.getExpireTime()).toMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttl));
            }
        } catch (RedisConnectionFailureException ignored) {
            // 回种失败仅影响下次查询性能，不影响校验结果
        }
        return true;
    }

    /** 待拉黑令牌信息（blacklistAll 入参） */
    public record TokenInfo(String jti, Long userId, LocalDateTime expireTime) {
    }
}
