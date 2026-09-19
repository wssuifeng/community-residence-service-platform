package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.notice.vo.NoticeVO;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-046 修复回归：游客公告可见性维度（GUEST 目标类型）。
 * 用户裁决口径：游客仅见「全平台广播 + 管理端显式开放游客的公告」，
 * 社区/楼栋定向公告对游客不可见；居民与管理端口径不回归；
 * GUEST 目标为全局级操作仅超管可发布（定义 v1.3 权责）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("公告游客可见性维度修复回归（DEF-046）")
class NoticeGuestVisibilityTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Notice.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, NoticeTarget.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, com.community.residence.notice.entity.NoticeViewRecord.class);
    }

    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeTargetMapper noticeTargetMapper;
    @Mock
    private NoticeViewRecordMapper viewRecordMapper;
    @Mock
    private com.community.residence.community.service.CommunityService communityService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private com.community.residence.community.mapper.BuildingMapper buildingMapper;

    @InjectMocks
    private NoticeService noticeService;

    private Notice publishedNotice;

    @BeforeEach
    void setUp() {
        publishedNotice = new Notice();
        publishedNotice.setId(1L);
        publishedNotice.setTitle("使用教程");
        publishedNotice.setContent("内容");
        publishedNotice.setPublisherId(2L);
        publishedNotice.setStatus("PUBLISHED");
        publishedNotice.setPublishTime(LocalDateTime.now().minusDays(1));
        publishedNotice.setEndTime(LocalDateTime.now().plusDays(1));
        publishedNotice.setViewCount(0);
        publishedNotice.setIsPinned(0);
    }

    private CreateNoticeDTO.TargetItemDTO guest() {
        CreateNoticeDTO.TargetItemDTO t = new CreateNoticeDTO.TargetItemDTO();
        t.setTargetType("GUEST");
        return t;
    }

    private CreateNoticeDTO.TargetItemDTO community(Long id) {
        CreateNoticeDTO.TargetItemDTO t = new CreateNoticeDTO.TargetItemDTO();
        t.setTargetType("COMMUNITY");
        t.setTargetId(id);
        return t;
    }

    private CreateNoticeDTO dto(List<CreateNoticeDTO.TargetItemDTO> targets) {
        CreateNoticeDTO dto = new CreateNoticeDTO();
        dto.setTargets(targets);
        dto.setTitle("游客可见公告");
        dto.setContent("内容");
        dto.setPublishTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        return dto;
    }

    private NoticeTarget target(String type, Long id) {
        NoticeTarget t = new NoticeTarget();
        t.setNoticeId(1L);
        t.setTargetType(type);
        t.setTargetId(id);
        return t;
    }

    private void stubNoticeInsert() {
        when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notice.class).setId(8L);
            return 1;
        });
        when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);
    }

    /* ---- 发布侧：GUEST 目标落库与全局级权限 ---- */

    @Test
    @DisplayName("超管发布 GUEST 目标：落 notice_target 一行 targetType=GUEST、targetId=0")
    void create_guestTarget_superAdmin_writesGuestRow() {
        stubNoticeInsert();

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);

            noticeService.create(dto(List.of(guest())));
        }
        ArgumentCaptor<NoticeTarget> captor = ArgumentCaptor.forClass(NoticeTarget.class);
        verify(noticeTargetMapper).insert(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo("GUEST");
        assertThat(captor.getValue().getTargetId()).isEqualTo(0L);
    }

    @Test
    @DisplayName("ADMIN 发布 GUEST 目标 → 403 拒绝（全局级操作，定义 v1.3 权责）且不落库")
    void create_guestTarget_admin_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);

            assertThatThrownBy(() -> noticeService.create(dto(List.of(community(1L), guest()))))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅超级管理员");
        }
        verify(noticeMapper, never()).insert(any(Notice.class));
        verify(noticeTargetMapper, never()).insert(any(NoticeTarget.class));
    }

    @Test
    @DisplayName("GUEST 目标携带 targetId → 400 拒绝（游客可见不指向具体对象）")
    void create_guestTarget_withTargetId_rejected() {
        CreateNoticeDTO.TargetItemDTO bad = guest();
        bad.setTargetId(5L);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);

            assertThatThrownBy(() -> noticeService.create(dto(List.of(bad))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("GUEST 目标不携带目标ID");
        }
    }

    @Test
    @DisplayName("草稿编辑为 GUEST 目标：先删后插重写（DEF-043 模式）落 GUEST 行")
    void update_guestTarget_rewritesTargets() {
        Notice draft = new Notice();
        draft.setId(1L);
        draft.setTitle("草稿");
        draft.setStatus("DRAFT");
        draft.setPublishTime(LocalDateTime.now().plusDays(1));
        when(noticeMapper.selectById(1L)).thenReturn(draft);
        when(noticeMapper.updateById(any(Notice.class))).thenReturn(1);
        when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("GUEST", 0L)));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);

            noticeService.update(1L, dto(List.of(guest())));
        }
        verify(noticeTargetMapper).delete(any());
        ArgumentCaptor<NoticeTarget> captor = ArgumentCaptor.forClass(NoticeTarget.class);
        verify(noticeTargetMapper).insert(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo("GUEST");
        assertThat(captor.getValue().getTargetId()).isEqualTo(0L);
    }

    /* ---- 游客查询：列表与详情同口径 ---- */

    @Test
    @DisplayName("游客列表：过滤条件含「无 COMMUNITY/BUILDING 定向 OR 存在 GUEST 目标」")
    void page_guestFilter_appliesGuestVisibleSql() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);
            when(noticeMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<Notice> p = inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            noticeService.page(1, 10, null, null, null, null, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notice>>
                    captor = ArgumentCaptor.forClass(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(noticeMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getSqlSegment();
            assertThat(sql).contains("NOT EXISTS");
            assertThat(sql).contains("target_type IN ('COMMUNITY', 'BUILDING')");
            assertThat(sql).contains("target_type = 'GUEST'");
        }
    }

    @Test
    @DisplayName("居民列表口径不回归：仍为「定向 OR 纯广播」，不引入 GUEST 条件")
    void page_residentFilter_unchanged() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
            mocked.when(SecurityUtils::getUserId).thenReturn(5L);
            /* 居民无在住社区 → 仅纯广播（BROADCAST_FILTER_SQL） */
            when(residenceRelationMapper.selectList(any())).thenReturn(List.of());
            when(noticeMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<Notice> p = inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            noticeService.page(1, 10, null, null, null, null, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notice>>
                    captor = ArgumentCaptor.forClass(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(noticeMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getSqlSegment();
            assertThat(sql).contains("WHERE nt.notice_id = notice.id)");
            assertThat(sql).as("居民口径不得混入 GUEST 维度").doesNotContain("GUEST");
        }
    }

    @Test
    @DisplayName("游客详情：纯广播公告 → 200（存量兼容，无需迁移）")
    void getById_guest_broadcast_ok() {
        when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);

            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("使用教程");
        }
    }

    @Test
    @DisplayName("游客详情：仅 GUEST 目标公告 → 200（显式开放游客）")
    void getById_guest_guestOnly_ok() {
        when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("GUEST", 0L)));
        when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);

            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("使用教程");
        }
    }

    @Test
    @DisplayName("游客详情：社区定向公告 → 404（原口径全量可见，本修复收口）")
    void getById_guest_communityTargeted_404() {
        when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("COMMUNITY", 1L)));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);

            assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    /* ---- 发布触达：GUEST-only 公告不向居民广播，纯广播口径不变 ---- */

    @Test
    @DisplayName("发布公告：仅 GUEST 目标 → 不向任何居民发通知（居民不可见，触达无意义）")
    void publish_guestOnlyNotice_notifiesNobody() {
        Notice draft = new Notice();
        draft.setId(5L);
        draft.setTitle("游客教程");
        draft.setStatus("DRAFT");
        draft.setPublisherId(1L);
        when(noticeMapper.selectById(5L)).thenReturn(draft);
        when(noticeMapper.updateById(any(Notice.class))).thenReturn(1);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of());
        when(noticeTargetMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);

            noticeService.publish(5L, LocalDateTime.now());
        }
        verify(residentMapper, never()).selectList(any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("发布公告：纯广播（无目标）→ 全员触达口径不变（居民不回归）")
    void publish_broadcastNotice_notifiesResidents() {
        Notice draft = new Notice();
        draft.setId(6L);
        draft.setTitle("全平台公告");
        draft.setStatus("DRAFT");
        draft.setPublisherId(1L);
        when(noticeMapper.selectById(6L)).thenReturn(draft);
        when(noticeMapper.updateById(any(Notice.class))).thenReturn(1);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of());
        when(noticeTargetMapper.selectCount(any())).thenReturn(0L);
        com.community.residence.resident.entity.Resident r1 =
                new com.community.residence.resident.entity.Resident();
        r1.setId(11L);
        com.community.residence.resident.entity.Resident r2 =
                new com.community.residence.resident.entity.Resident();
        r2.setId(12L);
        when(residentMapper.selectList(any())).thenReturn(List.of(r1, r2));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);

            noticeService.publish(6L, LocalDateTime.now());
        }
        verify(notificationService, times(2)).create(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
