package com.community.residence.messaging.service;

import com.community.residence.messaging.entity.Notification;
import com.community.residence.messaging.entity.NotificationChannelLog;
import com.community.residence.messaging.mapper.NotificationChannelLogMapper;
import com.community.residence.messaging.mapper.NotificationMapper;
import com.community.residence.resident.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 渠道分级配置与留痕测试（R51）：配置读写、非法渠道拒绝、勾选触发/未勾选不触发 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 渠道分级单元测试")
class ChannelLevelsTest {

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private NotificationChannelLogMapper channelLogMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;
    @Mock
    private SysConfigService sysConfigService;

    @InjectMocks
    private NotificationService notificationService;

    private void mockNotificationInsert() {
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(77L);
            return 1;
        });
        lenient().when(webSocketSessionService.isOnline(anyLong())).thenReturn(false);
        /* seq 生成走 Redis INCR */
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1001L);
    }

    @Test
    @DisplayName("配置读取：sys_config JSON 解析为等级映射")
    void readChannelLevels_parsesJson() {
        when(sysConfigService.getValue("notify.channel.levels"))
                .thenReturn("{\"IMPORTANT\":[\"SMS\",\"EMAIL\"],\"NORMAL\":[]}");

        Map<String, List<String>> levels = notificationService.readChannelLevels();

        assertThat(levels.get("IMPORTANT")).containsExactly("SMS", "EMAIL");
        assertThat(levels.get("NORMAL")).isEmpty();
    }

    @Test
    @DisplayName("配置缺失：回退空映射（仅站内必达）")
    void readChannelLevels_missing_returnsEmpty() {
        when(sysConfigService.getValue("notify.channel.levels")).thenReturn(null);
        assertThat(notificationService.readChannelLevels()).isEmpty();
    }

    @Test
    @DisplayName("非法渠道：PUT 更新被拒（仅 EMAIL/SMS）")
    void updateChannelLevels_invalidChannel_rejected() {
        assertThatThrownBy(() -> notificationService.updateChannelLevels(
                Map.of("IMPORTANT", List.of("PUSH"))))
                .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
                .hasMessageContaining("非法渠道");
        verify(sysConfigService, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("合法配置：经 upsert 写入 sys_config")
    void updateChannelLevels_valid_persisted() {
        notificationService.updateChannelLevels(Map.of("IMPORTANT", List.of("SMS")));
        verify(sysConfigService).upsert(eqKey(), anyString(), anyString());
    }

    private String eqKey() {
        return org.mockito.ArgumentMatchers.eq("notify.channel.levels");
    }

    @Test
    @DisplayName("勾选触发留痕：NOTICE 等级勾选 SMS → 通知生成写 WEBSOCKET + SMS 两条记录")
    void create_withConfiguredChannels_logsBoth() {
        when(sysConfigService.getValue("notify.channel.levels"))
                .thenReturn("{\"NOTICE\":[\"SMS\"]}");
        mockNotificationInsert();

        notificationService.create(10L, null, "公告", "内容", "NOTICE", "NOTICE", 1L);

        ArgumentCaptor<NotificationChannelLog> captor =
                ArgumentCaptor.forClass(NotificationChannelLog.class);
        verify(channelLogMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationChannelLog::getChannel)
                .containsExactlyInAnyOrder("WEBSOCKET", "SMS");
        assertThat(captor.getAllValues())
                .allMatch(log -> "SUCCESS".equals(log.getStatus())
                        && log.getNotificationId().equals(77L));
    }

    @Test
    @DisplayName("未勾选不触发：等级未配置模拟渠道 → 仅 WEBSOCKET 一条留痕（否定场景）")
    void create_withoutConfiguredChannels_logsWebsocketOnly() {
        when(sysConfigService.getValue("notify.channel.levels"))
                .thenReturn("{\"IMPORTANT\":[\"SMS\"],\"NORMAL\":[]}");
        mockNotificationInsert();

        notificationService.create(10L, null, "标题", "内容", "NORMAL", "NOTICE", 1L);

        ArgumentCaptor<NotificationChannelLog> captor =
                ArgumentCaptor.forClass(NotificationChannelLog.class);
        verify(channelLogMapper, times(1)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getChannel()).isEqualTo("WEBSOCKET");
    }

    @Test
    @DisplayName("配置 JSON 非法：回退仅站内，通知生成不受影响")
    void create_brokenConfig_fallsBackToWebsocketOnly() {
        when(sysConfigService.getValue("notify.channel.levels")).thenReturn("not-json{");
        mockNotificationInsert();

        notificationService.create(10L, null, "标题", "内容", "NOTICE", "NOTICE", 1L);

        verify(channelLogMapper, times(1)).insert(any(NotificationChannelLog.class));
    }
}
