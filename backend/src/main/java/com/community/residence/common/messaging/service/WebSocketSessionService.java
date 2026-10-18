package com.community.residence.common.messaging.service;

import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.context.IdentityPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * WebSocket 在线会话注册表（Redis key: ws:session:{主体名}，值为连接计数）。
 * 主体名带身份空间前缀（R:{residentId} / U:{sysUserId}，DEF-067 身份空间分离）：
 * resident 1 与 sys_user 1 主键撞号时不再共用 ws:session:1——否则二者的连接计数
 * 会互相顶掉（一方断开即把另一方判定为离线，推送丢失），且在线判定无法区分身份。
 * 连接计数语义（DEF-036）：register INCR + 续期、deregister DECR 归零才删 key——
 * 同一用户多连接（双标签页/多端登录）时关闭其一不断言离线，最后一个连接
 * 断开才注销；TTL 2 小时兜底：断开事件丢失时 key 自然过期，不产生僵尸在线。
 * Redis 不可用时 isOnline 一律返回 false（降级为不推送，HTTP 轮询兜底，N4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSessionService {

    private static final String KEY_PREFIX = "ws:session:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    /** 用户上线（CONNECT 后由事件监听器调用）：连接计数 +1 并续期 */
    public void register(String ownerType, Long userId) {
        try {
            String key = key(ownerType, userId);
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, TTL);
            log.debug("WebSocket 连接注册：ownerType={}, userId={}, connections={}", ownerType, userId, count);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，在线注册失败（推送将降级为轮询）：ownerType={}, userId={}", ownerType, userId);
        }
    }

    /** 用户下线（DISCONNECT 后由事件监听器调用）：连接计数 -1，归零才注销在线状态 */
    public void deregister(String ownerType, Long userId) {
        try {
            String key = key(ownerType, userId);
            Long count = redisTemplate.opsForValue().decrement(key);
            if (count != null && count <= 0) {
                redisTemplate.delete(key);
            }
            log.debug("WebSocket 连接注销：ownerType={}, userId={}, remaining={}", ownerType, userId, count);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，在线注销失败（TTL 兜底过期）：ownerType={}, userId={}", ownerType, userId);
        }
    }

    public boolean isOnline(String ownerType, Long userId) {
        try {
            String count = redisTemplate.opsForValue().get(key(ownerType, userId));
            return count != null && Long.parseLong(count) > 0;
        } catch (RedisConnectionFailureException e) {
            return false;
        } catch (NumberFormatException e) {
            /* 旧格式残留（历史 SET "1" 单值）按在线处理，register 后自然归一为计数格式 */
            return true;
        }
    }

    /** 在线键：与推送目的地（IdentityPrincipal.subjectName）同源，保证判定与投递同空间 */
    private String key(String ownerType, Long userId) {
        return KEY_PREFIX + IdentityPrincipal.subjectName(ownerType, userId);
    }

    /** 居民侧在线判定简写（resident.id） */
    public boolean isResidentOnline(Long residentId) {
        return isOnline(NotificationOwnerType.RESIDENT, residentId);
    }

    /** 员工/管理侧在线判定简写（sys_user.id） */
    public boolean isUserOnline(Long userId) {
        return isOnline(NotificationOwnerType.USER, userId);
    }
}
