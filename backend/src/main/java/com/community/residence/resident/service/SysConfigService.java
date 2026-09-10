package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.resident.entity.SysConfig;
import com.community.residence.resident.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/** 全局配置业务逻辑：sys_config 键值对，Redis 缓存 + DB 权威 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    public static final String KEY_REGISTRATION_ENABLED = "registration.enabled";
    public static final String KEY_LEASE_REMINDER_DAYS = "lease.reminder_days_before_expire";

    private static final String CACHE_PREFIX = "config:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;

    /** 读配置值（Redis 未命中查库回种；Redis 不可用降级查库） */
    public String getValue(String key) {
        String cacheKey = CACHE_PREFIX + key;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，配置直查 DB：key={}", key);
            return queryValue(key);
        }
        String value = queryValue(key);
        if (value != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL);
            } catch (RedisConnectionFailureException ignored) {
                // 回种失败不影响本次结果
            }
        }
        return value;
    }

    public List<SysConfig> listAll() {
        return sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .orderByAsc(SysConfig::getConfigKey));
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(String key, String value) {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (config == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "配置项不存在");
        }
        config.setConfigValue(value);
        sysConfigMapper.updateById(config);
        try {
            redisTemplate.delete(CACHE_PREFIX + key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，配置缓存未清理（1h TTL 自然过期）：key={}", key);
        }
        log.info("全局配置已更新：key={}, value={}", key, value);
    }

    /** 居民注册开关（缺省视为开启，配置键未初始化时不阻断注册） */
    public boolean isRegistrationEnabled() {
        String value = getValue(KEY_REGISTRATION_ENABLED);
        return value == null || Boolean.parseBoolean(value);
    }

    /** 插入或更新（键不存在则建；R51 渠道分级配置等后建键使用），缓存同步清理 */
    @Transactional(rollbackFor = Exception.class)
    public void upsert(String key, String value, String description) {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (config == null) {
            config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            sysConfigMapper.insert(config);
        } else {
            config.setConfigValue(value);
            sysConfigMapper.updateById(config);
        }
        try {
            redisTemplate.delete(CACHE_PREFIX + key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，配置缓存未清理（1h TTL 自然过期）：key={}", key);
        }
        log.info("全局配置已写入：key={}", key);
    }

    private String queryValue(String key) {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        return config != null ? config.getConfigValue() : null;
    }

    /** 供校验层判断配置键是否存在（StringUtils.hasText 语义） */
    public boolean exists(String key) {
        return StringUtils.hasText(getValue(key));
    }
}
