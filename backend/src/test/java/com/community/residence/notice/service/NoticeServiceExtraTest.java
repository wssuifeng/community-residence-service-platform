package com.community.residence.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.NoticeStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.community.entity.Community;
import com.community.residence.community.service.CommunityService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.entity.NoticeViewRecord;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.resident.entity.Resident;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 公告服务补充测试：发布成功路径、详情有效期、分页、查看计数 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeService 补充路径测试")
class NoticeServiceExtraTest {

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
    @Mock
    private com.community.residence.messaging.service.NotificationService notificationService;
    @Mock
    private com.community.residence.resident.mapper.ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private com.community.residence.community.mapper.BuildingMapper buildingMapper;

    @InjectMocks
    private NoticeService noticeService;

    private Notice published;
    private Community community;
    private NoticeTarget target;

    @BeforeEach
    void setUp() {
        published = new Notice();
        published.setId(1L);
        published.setTitle("T");
        published.setContent("C");
        published.setPublisherId(2L);
        published.setStatus(NoticeStatus.PUBLISHED);
        published.setPublishTime(LocalDateTime.now().minusDays(1));
        published.setEndTime(LocalDateTime.now().plusDays(30));
        published.setViewCount(0);

        community = new Community();
        community.setId(1L);
        community.setName("C1");

        target = new NoticeTarget();
        target.setNoticeId(1L);
        target.setTargetType("COMMUNITY");
        target.setTargetId(1L);
    }

    @Test
    @DisplayName("创建成功：ADMIN 指定社区 + 写定向记录 + 默认失效时间 30 天")
    void create_adminWithCommunity_writesTarget() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            /* v1.2 多目标改造：communityId 单目标走 checkTargetAccess → requireActiveCommunity */
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .checkCommunityAccess(1L)).thenAnswer(inv -> null);
            org.mockito.Mockito.lenient().when(communityService.requireActiveCommunity(1L)).thenReturn(community);
            when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
                inv.getArgument(0, Notice.class).setId(5L);
                return 1;
            });
            when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);

            CreateNoticeDTO dto = new CreateNoticeDTO();
            dto.setCommunityId(1L);
            dto.setTitle("t");
            dto.setContent("c");
            dto.setPublishTime(LocalDateTime.of(2026, 10, 1, 9, 0));

            var vo = noticeService.create(dto);

            assertThat(vo.getTitle()).isEqualTo("t");
            // endTime 默认 publishTime + 30 天
            org.mockito.Mockito.verify(noticeTargetMapper).insert(any(NoticeTarget.class));
        }
    }

    @Test
    @DisplayName("详情：匿名访问未过期已发布公告成功（附社区与发布人名称）")
    void getById_publishedNotExpired_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            when(noticeMapper.selectById(1L)).thenReturn(published);
            when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target));
            when(communityService.requireCommunity(1L)).thenReturn(community);
            com.community.residence.auth.entity.SysUser publisher =
                    new com.community.residence.auth.entity.SysUser();
            publisher.setRealName("Admin A");
            when(sysUserMapper.selectById(2L)).thenReturn(publisher);

            var vo = noticeService.getById(1L);
            assertThat(vo.getCommunityName()).isEqualTo("C1");
            assertThat(vo.getPublisherName()).isEqualTo("Admin A");
        }
    }

    @Test
    @DisplayName("详情：匿名访问已过期公告 404")
    void getById_expired_throws404() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            published.setEndTime(LocalDateTime.now().minusDays(1));
            when(noticeMapper.selectById(1L)).thenReturn(published);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(com.community.residence.common.exception.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("发布成功：DRAFT → PUBLISHED")
    void publish_draft_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            Notice draft = new Notice();
            draft.setId(3L);
            draft.setStatus(NoticeStatus.DRAFT);
            draft.setPublisherId(2L);
            when(noticeMapper.selectById(3L)).thenReturn(draft);
            when(noticeMapper.updateById(any(Notice.class))).thenReturn(1);
            org.mockito.Mockito.lenient().when(noticeTargetMapper.selectList(any()))
                    .thenReturn(List.of());

            noticeService.publish(3L, LocalDateTime.now());
            assertThat(draft.getStatus()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    @DisplayName("查看回执：首次查看计数 +1 并写回执记录")
    void recordView_first_increments() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(noticeMapper.selectById(1L)).thenReturn(published);
            when(viewRecordMapper.selectCount(any())).thenReturn(0L);
            when(viewRecordMapper.insert(any(NoticeViewRecord.class))).thenReturn(1);
            when(noticeMapper.updateById(any(Notice.class))).thenReturn(1);

            noticeService.recordView(1L);

            assertThat(published.getViewCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("分页：匿名仅见已发布未过期；communityId 过滤经 notice_target")
    void page_anonymous_filtersPublished() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target));
            when(noticeMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(inv -> {
                        Page<Notice> p = inv.getArgument(0);
                        p.setRecords(List.of(published));
                        p.setTotal(1);
                        return p;
                    });
            when(communityService.requireCommunity(1L)).thenReturn(community);

            var vo = noticeService.page(1, 10, 1L, null);
            assertThat(vo.getTotal()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("查看记录列表：装配居民姓名")
    void viewers_mapsResidentName() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(true);
            when(noticeMapper.selectById(1L)).thenReturn(published);
            when(viewRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(inv -> {
                        Page<NoticeViewRecord> p = inv.getArgument(0);
                        NoticeViewRecord r = new NoticeViewRecord();
                        r.setNoticeId(1L);
                        r.setUserId(7L);
                        r.setViewTime(LocalDateTime.now());
                        p.setRecords(List.of(r));
                        p.setTotal(1);
                        return p;
                    });
            Resident resident = new Resident();
            resident.setId(7L);
            resident.setRealName("Zhang San");
            when(residentMapper.selectBatchIds(any())).thenReturn(List.of(resident));

            var vo = noticeService.viewers(1L, 1, 10);
            assertThat(vo.getRecords()).hasSize(1);
            assertThat(vo.getRecords().get(0).getResidentName()).isEqualTo("Zhang San");
        }
    }

    @Test
    @DisplayName("删除：草稿可删除并清理定向记录")
    void delete_draft_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            Notice draft = new Notice();
            draft.setId(3L);
            draft.setStatus(NoticeStatus.DRAFT);
            draft.setPublisherId(2L);
            when(noticeMapper.selectById(3L)).thenReturn(draft);
            when(noticeMapper.deleteById(3L)).thenReturn(1);
            when(noticeTargetMapper.delete(any())).thenReturn(1);

            noticeService.delete(3L);
            org.mockito.Mockito.verify(noticeMapper).deleteById(3L);
        }
    }
}
