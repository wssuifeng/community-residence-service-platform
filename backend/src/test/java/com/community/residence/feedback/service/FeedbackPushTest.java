package com.community.residence.feedback.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.entity.FeedbackMessage;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.feedback.vo.FeedbackPushVO;
import com.community.residence.messaging.service.WebSocketSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R30 反馈会话 WebSocket 推送测试：在线广播触发、双方离线静默、推送失败不回滚 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService 会话推送单元测试")
class FeedbackPushTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private FeedbackMessageMapper messageMapper;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.messaging.service.NotificationService notificationService;
    @Mock
    private com.community.residence.auth.mapper.SysUserMapper sysUserMapper;

    @InjectMocks
    private FeedbackService feedbackService;

    private Feedback inSessionFeedback;

    @BeforeEach
    void setUp() {
        inSessionFeedback = new Feedback();
        inSessionFeedback.setId(1L);
        inSessionFeedback.setResidentId(10L);
        inSessionFeedback.setCommunityId(3L);
        inSessionFeedback.setTitle("T");
        inSessionFeedback.setStatus("IN_SESSION");
        inSessionFeedback.setHandlerId(5L);
    }

    private MockedStatic<SecurityUtils> mockAdmin(Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        return mocked;
    }

    @Test
    @DisplayName("管理员在线：落库后向 /topic/feedback/{id} 广播 FEEDBACK_MESSAGE")
    void sendMessage_residentOnline_pushes() {
        when(feedbackMapper.selectById(1L)).thenReturn(inSessionFeedback);
        when(messageMapper.insert(any(FeedbackMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, FeedbackMessage.class).setId(77L);
            return 1;
        });
        when(webSocketSessionService.isOnline(10L)).thenReturn(true);

        try (MockedStatic<SecurityUtils> ignored = mockAdmin(5L)) {
            var vo = feedbackService.sendMessage(1L, dto());
            assertThat(vo.getId()).isEqualTo(77L);
        }

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/feedback/1"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(FeedbackPushVO.class);
        FeedbackPushVO push = (FeedbackPushVO) payload.getValue();
        assertThat(push.getType()).isEqualTo("FEEDBACK_MESSAGE");
        assertThat(push.getData().getId()).isEqualTo(77L);
        assertThat(push.getData().getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("双方均离线：不推送（前端轮询兜底即 R30 离线补拉语义）")
    void sendMessage_bothOffline_noPush() {
        when(feedbackMapper.selectById(1L)).thenReturn(inSessionFeedback);
        when(messageMapper.insert(any(FeedbackMessage.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(anyLong())).thenReturn(false);

        try (MockedStatic<SecurityUtils> ignored = mockAdmin(5L)) {
            feedbackService.sendMessage(1L, dto());
        }
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("推送异常：吞掉不回滚，消息照常落库返回（轮询兜底）")
    void sendMessage_pushFailure_notPropagated() {
        when(feedbackMapper.selectById(1L)).thenReturn(inSessionFeedback);
        when(messageMapper.insert(any(FeedbackMessage.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(10L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        try (MockedStatic<SecurityUtils> ignored = mockAdmin(5L)) {
            var vo = feedbackService.sendMessage(1L, dto());
            assertThat(vo.getContent()).isEqualTo("hello");
        }
    }

    @Test
    @DisplayName("Redis 不可用降级（isOnline=false）：不推送（降级语义由 WebSocketSessionService 内部保证，已被双方离线用例覆盖）")
    void sendMessage_redisDown_degrades() {
        when(feedbackMapper.selectById(1L)).thenReturn(inSessionFeedback);
        when(messageMapper.insert(any(FeedbackMessage.class))).thenReturn(1);
        /* WebSocketSessionService.isOnline 在 Redis 不可用时内部捕获并返回 false，
           推送侧只需验证 false 时不推送 */
        when(webSocketSessionService.isOnline(anyLong())).thenReturn(false);

        try (MockedStatic<SecurityUtils> ignored = mockAdmin(5L)) {
            var vo = feedbackService.sendMessage(1L, dto());
            assertThat(vo.getContent()).isEqualTo("hello");
        }
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    private com.community.residence.feedback.dto.SendMessageDTO dto() {
        com.community.residence.feedback.dto.SendMessageDTO dto =
                new com.community.residence.feedback.dto.SendMessageDTO();
        dto.setContent("hello");
        return dto;
    }
}
