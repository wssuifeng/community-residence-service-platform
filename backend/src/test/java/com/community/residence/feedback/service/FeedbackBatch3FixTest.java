package com.community.residence.feedback.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.feedback.dto.CloseFeedbackDTO;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.entity.FeedbackMessage;
import com.community.residence.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.feedback.vo.FeedbackPushVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第三批修复回归：DEF-027（办结 WS 推送 + 列表 keyword） */
@ExtendWith(MockitoExtension.class)
@DisplayName("反馈第三批修复回归（DEF-027）")
class FeedbackBatch3FixTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private FeedbackMessageMapper messageMapper;
    @Mock
    private FeedbackAttachmentMapper attachmentMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;

    @InjectMocks
    private FeedbackService feedbackService;

    @Test
    @DisplayName("DEF-027：办结推送 FEEDBACK_STATUS 载荷（居民在线即广播，对端实时感知）")
    void close_pushesStatusPayload() {
        try (var mocked = org.mockito.Mockito.mockStatic(
                com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                    .thenReturn(5L);
            closeAndCapture();
        }
    }

    @Test
    @DisplayName("DEF-027：双方离线不推送（轮询兜底语义不变）")
    void close_bothOffline_noPush() {
        try (var mocked = org.mockito.Mockito.mockStatic(
                com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                    .thenReturn(5L);
            Feedback feedback = inSessionFeedback();
            when(feedbackMapper.selectById(1L)).thenReturn(feedback);
            when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);
            when(messageMapper.insert(any(FeedbackMessage.class))).thenReturn(1);
            when(webSocketSessionService.isOnline(10L)).thenReturn(false);
            when(webSocketSessionService.isOnline(5L)).thenReturn(false);

            var dto = new CloseFeedbackDTO();
            dto.setRemark("已解决");
            feedbackService.close(1L, dto);

            verify(messagingTemplate, org.mockito.Mockito.never())
                    .convertAndSend(any(String.class), any(Object.class));
        }
    }

    private void closeAndCapture() {
        Feedback feedback = inSessionFeedback();
        when(feedbackMapper.selectById(1L)).thenReturn(feedback);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);
        when(messageMapper.insert(any(FeedbackMessage.class))).thenReturn(1);
        when(webSocketSessionService.isOnline(10L)).thenReturn(true);

        var dto = new CloseFeedbackDTO();
        dto.setRemark("已解决");
        feedbackService.close(1L, dto);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/feedback/1"), payload.capture());
        FeedbackPushVO push = (FeedbackPushVO) payload.getValue();
        assertThat(push.getType()).isEqualTo("FEEDBACK_STATUS");
        FeedbackPushVO.FeedbackStatusVO data =
                (FeedbackPushVO.FeedbackStatusVO) push.getData();
        assertThat(data.getFeedbackId()).isEqualTo(1L);
        assertThat(data.getStatus()).isEqualTo("CLOSED");
    }

    private Feedback inSessionFeedback() {
        Feedback feedback = new Feedback();
        feedback.setId(1L);
        feedback.setResidentId(10L);
        feedback.setCommunityId(1L);
        feedback.setHandlerId(5L);
        feedback.setTitle("噪音问题");
        feedback.setStatus("IN_SESSION");
        return feedback;
    }
}
