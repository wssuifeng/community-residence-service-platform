package com.community.residence.feedback.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.entity.FeedbackAttachment;
import com.community.residence.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 反馈附件上传/删除测试（BE-ISSUE-9）：权限、办结校验、附件记录落库 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService 附件单元测试")
class FeedbackAttachmentTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private FeedbackAttachmentMapper attachmentMapper;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    @Mock
    private com.community.residence.messaging.service.WebSocketSessionService webSocketSessionService;

    @InjectMocks
    private FeedbackService feedbackService;

    private Feedback pendingFeedback;
    private Feedback closedFeedback;

    @BeforeEach
    void setUp() {
        pendingFeedback = new Feedback();
        pendingFeedback.setId(1L);
        pendingFeedback.setResidentId(4L);
        pendingFeedback.setCommunityId(3L);
        pendingFeedback.setStatus("IN_SESSION");

        closedFeedback = new Feedback();
        closedFeedback.setId(2L);
        closedFeedback.setResidentId(4L);
        closedFeedback.setCommunityId(3L);
        closedFeedback.setStatus("CLOSED");
    }

    private MockedStatic<SecurityUtils> mockLogin(String role, Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(role)).thenReturn(true);
        return mocked;
    }

    @Test
    @DisplayName("居民上传本人反馈附件：成功（BE-ISSUE-9 核心场景）")
    void upload_residentOwner_success() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 4L)) {
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);
            when(fileUploadService.uploadAutoType(any()))
                    .thenReturn(new FileUploadService.UploadResult("f1", "pic.png", "/uploads/2026/09/09/p.png", 120L, "IMAGE"));
            when(attachmentMapper.insert(any(FeedbackAttachment.class))).thenReturn(1);

            var vo = feedbackService.uploadAttachment(1L,
                    new MockMultipartFile("file", "pic.png", "image/png", new byte[10]));

            assertThat(vo.getFileUrl()).isEqualTo("/uploads/2026/09/09/p.png");
        }
    }

    @Test
    @DisplayName("居民上传他人反馈附件：403")
    void upload_residentOther_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 99L)) {
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);

            assertThatThrownBy(() -> feedbackService.uploadAttachment(1L,
                    new MockMultipartFile("file", "pic.png", "image/png", new byte[10])))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("已办结反馈上传附件：业务拒绝")
    void upload_closedFeedback_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 4L)) {
            when(feedbackMapper.selectById(2L)).thenReturn(closedFeedback);

            assertThatThrownBy(() -> feedbackService.uploadAttachment(2L,
                    new MockMultipartFile("file", "pic.png", "image/png", new byte[10])))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("删除附件：本人 + 会话中 → 成功")
    void delete_owner_success() {
        FeedbackAttachment attachment = new FeedbackAttachment();
        attachment.setId(21L);
        attachment.setFeedbackId(1L);
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 4L)) {
            when(attachmentMapper.selectById(21L)).thenReturn(attachment);
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);
            when(attachmentMapper.deleteById(21L)).thenReturn(1);

            feedbackService.deleteAttachment(21L);
        }
    }

    @Test
    @DisplayName("删除不存在的附件：404 语义（ResourceNotFound）")
    void delete_notFound_throws() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 4L)) {
            when(attachmentMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> feedbackService.deleteAttachment(999L))
                    .isInstanceOf(com.community.residence.common.exception.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("居民删除他人反馈附件：403")
    void delete_residentOther_forbidden() {
        FeedbackAttachment attachment = new FeedbackAttachment();
        attachment.setId(21L);
        attachment.setFeedbackId(1L);
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 99L)) {
            when(attachmentMapper.selectById(21L)).thenReturn(attachment);
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);

            assertThatThrownBy(() -> feedbackService.deleteAttachment(21L))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("管理员上传任意社区反馈附件：成功")
    void upload_admin_success() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("ADMIN", 5L)) {
            when(feedbackMapper.selectById(1L)).thenReturn(pendingFeedback);
            when(fileUploadService.uploadAutoType(any()))
                    .thenReturn(new FileUploadService.UploadResult("f2", "doc.pdf", "/uploads/2026/09/09/d.pdf", 220L, "DOCUMENT"));
            when(attachmentMapper.insert(any(FeedbackAttachment.class))).thenReturn(1);

            var vo = feedbackService.uploadAttachment(1L,
                    new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[10]));

            assertThat(vo.getFileName()).isEqualTo("doc.pdf");
        }
    }
}
