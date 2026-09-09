package com.community.residence.messaging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * WebSocket 在线会话注册表（Redis key: ws:session:{userId}）。
 * TTL 2 小时兜底：连接断开事件丢失时 key 自然过期，不产生僵尸在线。
 * Redis 不可用时 isOnline 一律返回 false（降级为不推送，HTTP 轮询兜底，N4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSessionService {

    private static final String KEY_PREFIX = "ws:session:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    /** 用户上线（CONNECT 后由事件监听器调用） */
    public void register(Long userId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", TTL);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，在线注册失败（推送将降级为轮询）：userId={}", userId);
        }
    }

    /** 用户下线（DISCONNECT 后由事件监听器调用） */
    public void deregister(Long userId) {
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，在线注销失败（TTL 兜底过期）：userId={}", userId);
        }
    }

    public boolean isOnline(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
        } catch (RedisConnectionFailureException e) {
            return false;
        }
    }
}
