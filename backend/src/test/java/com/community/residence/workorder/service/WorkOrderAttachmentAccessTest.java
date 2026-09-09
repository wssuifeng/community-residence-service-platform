package com.community.residence.workorder.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.entity.WorkOrderAttachment;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 工单附件权限矩阵测试（BE-ISSUE-10）：居民=提交人、服务人员=被派单人、管理员放行 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService 附件权限单元测试")
class WorkOrderAttachmentAccessTest {

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderAttachmentMapper attachmentMapper;
    @Mock
    private WorkOrderAssignmentMapper assignmentMapper;
    @Mock
    private FileUploadService fileUploadService;

    @InjectMocks
    private WorkOrderService workOrderService;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(1L);
        order.setResidentId(1L);
        order.setCommunityId(1L);
        order.setStatus("IN_PROGRESS");
    }

    private MockedStatic<SecurityUtils> mockLogin(String role, Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(role)).thenReturn(true);
        return mocked;
    }

    @Test
    @DisplayName("居民上传本人工单附件：成功")
    void upload_residentOwner_success() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 1L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(fileUploadService.uploadAutoType(any()))
                    .thenReturn(new FileUploadService.UploadResult("f1", "a.jpg", "/uploads/2026/09/09/a.jpg", 100L, "IMAGE"));
            when(attachmentMapper.insert(any(WorkOrderAttachment.class))).thenReturn(1);

            var vo = workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10]));

            assertThat(vo.getFileUrl()).isEqualTo("/uploads/2026/09/09/a.jpg");
        }
    }

    @Test
    @DisplayName("居民上传他人工单附件：403")
    void upload_residentOther_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("RESIDENT", 99L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);

            assertThatThrownBy(() -> workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("服务人员上传派给自己的工单附件：成功（BE-ISSUE-10 核心场景）")
    void upload_staffAssignee_success() {
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(1L);
        assignment.setAssigneeId(7L);
        try (MockedStatic<SecurityUtils> mocked = mockLogin("STAFF", 7L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);
            when(fileUploadService.uploadAutoType(any()))
                    .thenReturn(new FileUploadService.UploadResult("f2", "site.jpg", "/uploads/2026/09/09/s.jpg", 200L, "IMAGE"));
            when(attachmentMapper.insert(any(WorkOrderAttachment.class))).thenReturn(1);

            var vo = workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "site.jpg", "image/jpeg", new byte[10]));

            assertThat(vo.getFileName()).isEqualTo("site.jpg");
        }
    }

    @Test
    @DisplayName("服务人员上传未派给自己的工单附件：403")
    void upload_staffNotAssignee_forbidden() {
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(1L);
        assignment.setAssigneeId(8L);
        try (MockedStatic<SecurityUtils> mocked = mockLogin("STAFF", 7L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);

            assertThatThrownBy(() -> workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("无派单记录时服务人员上传：403")
    void upload_staffNoAssignment_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("STAFF", 7L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(assignmentMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("管理员上传任意工单附件：成功")
    void upload_admin_success() {
        try (MockedStatic<SecurityUtils> mocked = mockLogin("ADMIN", 5L)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(fileUploadService.uploadAutoType(any()))
                    .thenReturn(new FileUploadService.UploadResult("f3", "b.png", "/uploads/2026/09/09/b.png", 300L, "IMAGE"));
            when(attachmentMapper.insert(any(WorkOrderAttachment.class))).thenReturn(1);

            var vo = workOrderService.uploadAttachment(1L,
                    new MockMultipartFile("file", "b.png", "image/png", new byte[10]));

            assertThat(vo.getFileUrl()).isNotBlank();
        }
    }

    @Test
    @DisplayName("服务人员删除派给自己的工单附件：成功")
    void delete_staffAssignee_success() {
        WorkOrderAttachment attachment = new WorkOrderAttachment();
        attachment.setId(11L);
        attachment.setWorkOrderId(1L);
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(1L);
        assignment.setAssigneeId(7L);
        try (MockedStatic<SecurityUtils> mocked = mockLogin("STAFF", 7L)) {
            when(attachmentMapper.selectById(11L)).thenReturn(attachment);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);
            when(attachmentMapper.deleteById(11L)).thenReturn(1);

            workOrderService.deleteAttachment(11L);
        }
    }
}
