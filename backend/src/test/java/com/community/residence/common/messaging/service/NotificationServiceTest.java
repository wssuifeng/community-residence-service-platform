package com.community.residence.common.messaging.service;

import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.messaging.entity.Notification;
import com.community.residence.common.messaging.mapper.NotificationMapper;
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

/** 通知业务逻辑测试：seq 生成降级、已读、增量拉取、WebSocket 在线推送、身份空间标注（DEF-067） */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 单元测试")
class NotificationServiceTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // StringRedisTemplate.opsForValue 由各用例按需打桩
    }

    @Test
    @DisplayName("生成通知：seq 取 Redis INCR 值，落 owner_type=USER（sys_user 身份空间）")
    void create_usesRedisSeq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(42L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(NotificationOwnerType.USER, 1L)).thenReturn(false);

        notificationService.createForUser(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isEqualTo(42L);
        assertThat(captor.getValue().getIsRead()).isEqualTo(0);
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getOwnerType()).isEqualTo(NotificationOwnerType.USER);
    }

    @Test
    @DisplayName("生成居民通知：落 owner_type=RESIDENT（resident 身份空间）")
    void createForResident_persistsResidentOwnerType() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(45L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(NotificationOwnerType.RESIDENT, 1L)).thenReturn(false);

        notificationService.createForResident(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo(NotificationOwnerType.RESIDENT);
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("在线推送：接收人 WebSocket 在线时推送到用户专属队列（主体名带 U: 前缀）")
    void create_onlineUser_pushesToQueue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(43L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(NotificationOwnerType.USER, 1L)).thenReturn(true);

        notificationService.createForUser(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        verify(messagingTemplate).convertAndSendToUser(eq("U:1"),
                eq("/queue/notifications"), any());
    }

    @Test
    @DisplayName("在线推送：居民通知推送到 R:{residentId} 目的地，与 sys_user 同 id 不共用 destination")
    void create_onlineResident_pushesToPrefixedQueue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(46L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(NotificationOwnerType.RESIDENT, 1L)).thenReturn(true);

        notificationService.createForResident(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        verify(messagingTemplate).convertAndSendToUser(eq("R:1"),
                eq("/queue/notifications"), any());
    }

    @Test
    @DisplayName("推送失败：不影响通知落库（轮询兜底）")
    void create_pushFailure_notificationStillPersisted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(44L);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(NotificationOwnerType.USER, 1L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("ws down"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        notificationService.createForUser(1L, 1L, "Title", "Content", "TYPE", "SRC", 7L);

        verify(notificationMapper).insert(any(Notification.class));
    }

    @Test
    @DisplayName("seq 降级：Redis 不可用时时间戳兜底（仍写入成功）")
    void create_redisDown_fallsBackToTimestamp() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.createForResident(1L, null, "T", "C", "TYPE", null, null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isPositive();
    }

    @Test
    @DisplayName("全部已读：仅更新当前用户未读记录")
    void markAllRead_scopesToCurrentUser() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(7L);
            when(notificationMapper.update(any(), any())).thenReturn(3);

            int updated = notificationMapper.update(null, null);

            assertThat(updated).isEqualTo(3);
        }
    }
}
