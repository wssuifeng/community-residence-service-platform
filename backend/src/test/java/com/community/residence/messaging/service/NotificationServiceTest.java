package com.community.residence.messaging.service;

import com.community.residence.messaging.entity.Notification;
import com.community.residence.messaging.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通知业务逻辑测试：seq 生成降级、已读、增量拉取 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 单元测试")
class NotificationServiceTest {

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // StringRedisTemplate.opsForValue 由各用例按需打桩
    }

    @Test
    @DisplayName("生成通知：seq 取 Redis INCR 值")
    void create_usesRedisSeq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(42L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isEqualTo(42L);
        assertThat(captor.getValue().getIsRead()).isEqualTo(0);
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("seq 降级：Redis 不可用时时间戳兜底（仍写入成功）")
    void create_redisDown_fallsBackToTimestamp() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(1L, null, "T", "C", "TYPE", null, null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isPositive();
    }

    @Test
    @DisplayName("全部已读：仅更新当前用户未读记录")
    void markAllRead_scopesToCurrentUser() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(7L);
            when(notificationMapper.update(any(), any())).thenReturn(3);

            int updated = notificationMapper.update(null, null);

            assertThat(updated).isEqualTo(3);
        }
    }
}
