package com.community.residence.common.config;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 统一覆盖缓存、黑名单、配置、在线状态和Lua序号访问的故障等待保护。 */
public class FailFastStringRedisTemplate extends StringRedisTemplate {
    private final RedisCacheGuard guard = new RedisCacheGuard();
    public FailFastStringRedisTemplate(RedisConnectionFactory factory) { super(factory); }

    @Override
    public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
        return guard.execute(() -> super.execute(action, exposeConnection, pipeline));
    }
}
