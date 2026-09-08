package com.community.residence.feedback.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.feedback.dto.CreateFeedbackDTO;
import com.community.residence.feedback.dto.SendMessageDTO;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 反馈业务逻辑测试：受理状态机、办结终态、越权拦截 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService 单元测试")
class FeedbackServiceTest {

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

    @InjectMocks
    private FeedbackService feedbackService;

    private Feedback pendingFeedback;
    private Feedback closedFeedback;

    @BeforeEach
    void setUp() {
        pendingFeedback = new Feedback();
        pendingFeedback.setId(1L);
        pendingFeedback.setResidentId(1L);
        pendingFeedback.setCommunityId(1L);
        pendingFeedback.setTitle("T");
        pendingFeedback.setStatus("PENDING");

        closedFeedback = new Feedback();
        closedFeedback.setId(2L);
        closedFeedback.setResidentId(1L);
        closedFeedback.setCommunityId(1L);
        closedFeedback.setStatus("CLOSED");
    }

    @Test
    @DisplayName("提交反馈：初始 PENDING 状态")
    void create_initialPending() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(feedbackMapper.insert(any(Feedback.class))).thenAnswer(inv -> {
                inv.getArgument(0, Feedback.class).setId(9L);
                return 1;
            });

            CreateFeedbackDTO dto = new CreateFeedbackDTO();
            dto.setCommunityId(1L);
            dto.setTitle("Noise");
            dto.setContent("loud");
            dto.setCategory("COMPLAINT");

            var vo = feedbackService.create(dto);
            assertThat(vo.getStatus()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("办结终态：CLOSED 不可继续会话")
    void sendMessage_closed_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(feedbackMapper.selectById(2L)).thenReturn(closedFeedback);

            assertThatThrownBy(() -> feedbackService.sendMessage(2L, msgDto()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已办结");
        }
    }

    @Test
    @DisplayName("管理员首次回复：自动受理（PENDING → IN_SESSION）")
    void sendMessage_adminFirstReply_autoAccepts() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            // 非居民 = 管理端
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(false);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);
            when(feedbackMapper.updateById(any(com.community.residence.feedback.entity.Feedback.class))).thenReturn(1);
            when(messageMapper.insert(any(com.community.residence.feedback.entity.FeedbackMessage.class))).thenReturn(1);

            feedbackService.sendMessage(1L, msgDto());

            assertThat(pendingFeedback.getStatus()).isEqualTo("IN_SESSION");
            assertThat(pendingFeedback.getHandlerId()).isEqualTo(5L);
        }
    }

    @Test
    @DisplayName("居民越权：在他人反馈下发消息拒绝（403）")
    void sendMessage_otherResidentFeedback_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(999L);
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);

            assertThatThrownBy(() -> feedbackService.sendMessage(1L, msgDto()))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    private SendMessageDTO msgDto() {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent("hello");
        return dto;
    }
}
