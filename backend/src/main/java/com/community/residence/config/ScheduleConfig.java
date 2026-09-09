package com.community.residence.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务配置：单实例内 @Scheduled 调度（N9 单机约束，无需调度中间件）。
 * 多实例防重入由各任务内 Redisson 分布式锁保证，RedissonClient
 * 由 redisson-spring-boot-starter 自动装配，任务类直接注入。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}
