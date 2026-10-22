package com.community.residence.common.feedback.service;

import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.feedback.dto.SendMessageDTO;
import com.community.residence.common.feedback.entity.Feedback;
import com.community.residence.common.feedback.entity.FeedbackMessage;
import com.community.residence.common.feedback.mapper.FeedbackMapper;
import com.community.residence.common.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 反馈异常组合：拒绝发生在任何写入/通知之前，居民追问按实际处理人触达。 */
@ExtendWith(MockitoExtension.class)
class FeedbackConversationBoundaryTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();
    @Mock FeedbackMapper feedbackMapper;
    @Mock FeedbackMessageMapper messageMapper;
    @Mock ResidentMapper residentMapper;
    @Mock NotificationService notificationService;
    @Mock WebSocketSessionService webSocketSessionService;
    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks FeedbackService service;

    private Feedback feedback(String state, Long handler) {
        Feedback feedback = new Feedback(); feedback.setId(12L); feedback.setResidentId(7L);
        feedback.setCommunityId(3L); feedback.setStatus(state); feedback.setHandlerId(handler);
        feedback.setTitle("合成反馈"); return feedback;
    }
    private SendMessageDTO message() {
        SendMessageDTO dto = new SendMessageDTO(); dto.setContent("继续说明"); return dto;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void closedFeedbackRejectsBothSidesWithoutWritesOrNotifications(boolean resident) {
        when(feedbackMapper.selectByIdForUpdate(12L)).thenReturn(feedback("CLOSED", 9L));
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(resident);
            identity.when(SecurityUtils::getUserId).thenReturn(7L);
            assertThatThrownBy(() -> service.sendMessage(12L, message())).isInstanceOf(BusinessException.class);
            verify(feedbackMapper, never()).updateById(any(Feedback.class));
            verifyNoInteractions(messageMapper, notificationService, messagingTemplate, webSocketSessionService);
        }
    }

    @Test void unboundCommunityAdminIsDeniedBeforeAcceptingFeedback() {
        when(feedbackMapper.selectByIdForUpdate(12L)).thenReturn(feedback("PENDING", null));
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(() -> SecurityUtils.checkCommunityAccess(3L)).thenThrow(new ForbiddenException("小区无权限"));
            assertThatThrownBy(() -> service.sendMessage(12L, message())).isInstanceOf(ForbiddenException.class);
            verify(feedbackMapper, never()).updateById(any(Feedback.class));
            verifyNoInteractions(messageMapper, notificationService, messagingTemplate, webSocketSessionService);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void residentFollowupNotifiesOnlyExistingHandlerAndDoesNotAutoAccept(boolean assigned) {
        when(feedbackMapper.selectByIdForUpdate(12L)).thenReturn(feedback(assigned ? "IN_SESSION" : "PENDING", assigned ? 9L : null));
        when(messageMapper.insert(any(FeedbackMessage.class))).thenAnswer(call -> {
            FeedbackMessage stored = call.getArgument(0);
            assertThat(stored.getSenderType()).isEqualTo("RESIDENT");
            assertThat(stored.getSenderId()).isEqualTo(7L);
            assertThat(stored.getContent()).isEqualTo("继续说明");
            stored.setId(22L); return 1;
        });
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
            identity.when(SecurityUtils::getUserId).thenReturn(7L);
            assertThat(service.sendMessage(12L, message()).getId()).isEqualTo(22L);
            if (assigned) verify(notificationService).createForUser(eq(9L), eq(3L), anyString(), anyString(), eq("FEEDBACK"), eq("FEEDBACK"), eq(12L));
            else verifyNoInteractions(notificationService);
            verify(feedbackMapper, never()).updateById(any(Feedback.class));
            verifyNoInteractions(messagingTemplate);
        }
    }
}
