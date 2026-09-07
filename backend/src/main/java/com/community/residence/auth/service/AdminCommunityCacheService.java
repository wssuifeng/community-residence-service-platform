package com.community.residence.auth.service;

import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 管理员绑定社区缓存（架构设计.md §3.2 权限数据存储策略）：
 * DB（sys_admin_community）为权威来源，Redis 缓存 TTL 2h 与 Access Token 同步；
 * 绑定变更时由变更接口删除缓存并吊销令牌。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCommunityCacheService {

    private static final String KEY_PREFIX = "user:";
    private static final String KEY_SUFFIX = ":communities";

    private final SysAdminCommunityMapper adminCommunityMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 读取管理员绑定社区集合：Redis 未命中查库回种；Redis 不可用直接查库。
     */
    public Set<Long> getCommunityIds(Long adminId) {
        String key = KEY_PREFIX + adminId + KEY_SUFFIX;
        try {
            var cached = redisTemplate.opsForSet().members(key);
            if (cached != null && !cached.isEmpty()) {
                Set<Long> ids = new HashSet<>();
                for (String member : cached) {
                    ids.add(Long.valueOf(member));
                }
                return ids;
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，绑定社区直查 DB：adminId={}", adminId);
            return Set.copyOf(adminCommunityMapper.selectCommunityIdsByAdminId(adminId));
        }

        Set<Long> ids = Set.copyOf(adminCommunityMapper.selectCommunityIdsByAdminId(adminId));
        try {
            if (ids.isEmpty()) {
                // 空集合也缓存，防止无绑定管理员每次请求穿透查库
                redisTemplate.opsForSet().add(key, "-1");
                redisTemplate.expire(key, Duration.ofHours(2));
            } else {
                String[] members = ids.stream().map(String::valueOf).toArray(String[]::new);
                redisTemplate.opsForSet().add(key, members);
                redisTemplate.expire(key, Duration.ofHours(2));
            }
        } catch (RedisConnectionFailureException ignored) {
            // 回种失败不影响本次结果
        }
        return ids;
    }

    /** 绑定关系变更后清缓存（下次请求自动回种新数据） */
    public void evict(Long adminId) {
        try {
            redisTemplate.delete(KEY_PREFIX + adminId + KEY_SUFFIX);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，绑定社区缓存未清理（2h TTL 自然过期）：adminId={}", adminId);
        }
    }
}
