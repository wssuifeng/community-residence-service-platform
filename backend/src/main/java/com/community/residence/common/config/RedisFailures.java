package com.community.residence.common.config;

import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Redis 传输故障识别：兼容 Redisson 的异常包装，编解码与命令错误仍向外抛出。 */
public final class RedisFailures {

    private RedisFailures() {
    }

    /* 驱动可能将连接拒绝包装为 InvalidDataAccessApiUsageException；只按传输原因降级，
       不吞掉错误命令、序列化或数据库查询异常，鉴权仍以数据库为权威。 */
    public static void rethrowUnlessUnavailable(DataAccessException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException
                    || cause instanceof RedisConnectionException
                    || cause instanceof RedisTimeoutException
                    || cause instanceof SocketException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return;
            }
        }
        throw failure;
    }
}
