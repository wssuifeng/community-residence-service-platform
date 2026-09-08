package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.NoticeStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.service.CommunityService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 公告业务逻辑测试：状态机（草稿/发布/撤回）、定向、有效期、查看去重 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeService 单元测试")
class NoticeServiceTest {

    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeTargetMapper noticeTargetMapper;
    @Mock
    private NoticeViewRecordMapper viewRecordMapper;
    @Mock
    private CommunityService communityService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ResidentMapper residentMapper;

    @InjectMocks
    private NoticeService noticeService;

    private Notice draftNotice;
    private Notice publishedNotice;

    @BeforeEach
    void setUp() {
        draftNotice = new Notice();
        draftNotice.setId(1L);
        draftNotice.setTitle("T1");
        draftNotice.setStatus(NoticeStatus.DRAFT);
        draftNotice.setPublisherId(2L);
        draftNotice.setViewCount(0);

        publishedNotice = new Notice();
        publishedNotice.setId(2L);
        publishedNotice.setTitle("T2");
        publishedNotice.setStatus(NoticeStatus.PUBLISHED);
        publishedNotice.setPublisherId(2L);
        publishedNotice.setViewCount(0);
    }

    @Test
    @DisplayName("创建公告：社区管理员必须指定目标社区")
    void create_adminWithoutCommunity_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(false);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);

            CreateNoticeDTO dto = new CreateNoticeDTO();
            dto.setTitle("t");
            dto.setContent("c");
            dto.setPublishTime(LocalDateTime.now().plusDays(1));

            assertThatThrownBy(() -> noticeService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须指定目标社区");
        }
    }

    @Test
    @DisplayName("状态机：已发布公告不可再发布（重复发布拒绝）")
    void publish_alreadyPublished_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            when(noticeMapper.selectById(2L)).thenReturn(publishedNotice);

            assertThatThrownBy(() -> noticeService.publish(2L, LocalDateTime.now()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅草稿状态");
        }
    }

    @Test
    @DisplayName("状态机：撤回仅限已发布（草稿撤回拒绝）")
    void withdraw_draft_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            when(noticeMapper.selectById(1L)).thenReturn(draftNotice);

            assertThatThrownBy(() -> noticeService.withdraw(1L, "mistake"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅已发布");
        }
    }

    @Test
    @DisplayName("管理员越权：ADMIN 管理无目标（广播）公告拒绝")
    void manageAdmin_broadcastNotice_forbidden() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(false);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .getCommunityIds()).thenReturn(java.util.Set.of(1L));
            when(noticeMapper.selectById(1L)).thenReturn(draftNotice);
            // 无 notice_target 记录（全系统广播）
            when(noticeTargetMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> noticeService.withdraw(1L, "x"))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("查看去重：同一用户重复查看不重复计数")
    void recordView_duplicate_notCounted() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(noticeMapper.selectById(2L)).thenReturn(publishedNotice);
            when(viewRecordMapper.selectCount(any())).thenReturn(1L);

            noticeService.recordView(2L);

            assertThat(publishedNotice.getViewCount()).isEqualTo(0);
            org.mockito.Mockito.verify(viewRecordMapper, org.mockito.Mockito.never())
                    .insert(any(com.community.residence.notice.entity.NoticeViewRecord.class));
        }
    }
}
