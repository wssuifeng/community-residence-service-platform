package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * DEF-003/004 修复回归：公告读路径数据级过滤（R25 范围外不可见）
 * 与 communityId 单目标写法越权校验（R24 v1.2）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("公告读路径过滤与单目标越权修复回归（DEF-003/004）")
class NoticeDataScopeFixTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Notice.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, NoticeTarget.class);
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
    private CommunityService communityService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private BuildingMapper buildingMapper;

    @InjectMocks
    private NoticeService noticeService;

    private Notice publishedNotice;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        publishedNotice = new Notice();
        publishedNotice.setId(1L);
        publishedNotice.setTitle("清源里定向公告");
        publishedNotice.setContent("内容");
        publishedNotice.setPublisherId(2L);
        publishedNotice.setStatus("PUBLISHED");
        publishedNotice.setPublishTime(LocalDateTime.now().minusDays(1));
        publishedNotice.setEndTime(LocalDateTime.now().plusDays(1));
        publishedNotice.setViewCount(0);
        publishedNotice.setIsPinned(0);
    }

    /* ---- DEF-003①：管理员 GET 详情越绑定社区 404 ---- */

    @Test
    @DisplayName("ADMIN 读详情：公告目标社区不在绑定集合内 → 404（原 200 全文放行）")
    void getById_adminCrossCommunity_throws() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        /* 公告定向社区 1，admin2 仅绑定社区 2 */
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(
                target("COMMUNITY", 1L)));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "admin2", "ADMIN", Set.of(2L)));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);
            mocked.when(SecurityUtils::getCommunityIds).thenReturn(Set.of(2L));

            assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("ADMIN 读详情：目标社区在绑定集合内 → 200")
    void getById_adminBoundCommunity_ok() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(
                target("COMMUNITY", 2L)));
        lenient().when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "admin2", "ADMIN", Set.of(2L)));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(false);
            mocked.when(SecurityUtils::getCommunityIds).thenReturn(Set.of(2L));

            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("清源里定向公告");
        }
    }

    /* ---- DEF-003②：居民范围外公告不可见 ---- */

    @Test
    @DisplayName("居民读详情：公告定向其他社区 → 404（R25 范围外不可见）")
    void getById_residentOutOfScope_throws() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(
                target("COMMUNITY", 2L)));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
            mocked.when(SecurityUtils::getUserId).thenReturn(5L);
            /* 居民在住社区 1，公告定向社区 2 */
            lenient().when(residenceRelationMapper.selectList(any())).thenReturn(List.of(relation(5L, 1L)));

            assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("居民读详情：公告定向本人社区 → 200")
    void getById_residentInScope_ok() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(
                target("COMMUNITY", 1L)));
        lenient().when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
            mocked.when(SecurityUtils::getUserId).thenReturn(5L);
            lenient().when(residenceRelationMapper.selectList(any())).thenReturn(List.of(relation(5L, 1L)));

            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("清源里定向公告");
        }
    }

    @Test
    @DisplayName("居民读详情：无目标全系统广播 → 200")
    void getById_residentBroadcast_ok() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
            mocked.when(SecurityUtils::getUserId).thenReturn(5L);
            lenient().when(residenceRelationMapper.selectList(any())).thenReturn(List.of(relation(5L, 1L)));

            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("清源里定向公告");
        }
    }

    /* ---- DEF-004：communityId 单目标写法越权校验 ---- */

    @Test
    @DisplayName("创建公告：communityId 单目标指向未绑定社区 → 403（原 200 落库越权定向）")
    void create_communityIdOutOfScope_throws() {
        CreateNoticeDTO dto = new CreateNoticeDTO();
        dto.setTitle("越权定向公告");
        dto.setContent("内容");
        dto.setCommunityId(2L);
        dto.setPublishTime(LocalDateTime.now());

        /* community 2 存在且运营中（requireActiveCommunity 放行），越权在绑定校验暴露 */
        lenient().when(communityService.requireActiveCommunity(2L)).thenReturn(new Community());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(1L, "admin1", "ADMIN", Set.of(1L)));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            /* checkCommunityAccess 为 void 静态方法，mockStatic 默认 no-op，
               越权用例按项目惯例显式 thenThrow（真实链路归 SP-01 独立实例冒烟） */
            mocked.when(() -> SecurityUtils.checkCommunityAccess(2L))
                    .thenThrow(new ForbiddenException("无权操作未绑定社区的数据"));

            assertThatThrownBy(() -> noticeService.create(dto))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权操作未绑定社区");
        }
    }

    @Test
    @DisplayName("创建公告：communityId 单目标指向绑定社区 → 正常落库")
    void create_communityIdBound_ok() {
        CreateNoticeDTO dto = new CreateNoticeDTO();
        dto.setTitle("本社区公告");
        dto.setContent("内容");
        dto.setCommunityId(1L);
        dto.setPublishTime(LocalDateTime.now());

        lenient().when(communityService.requireActiveCommunity(1L)).thenReturn(new Community());
        lenient().when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notice.class).setId(99L);
            return 1;
        });
        lenient().when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("COMMUNITY", 1L)));
        lenient().when(sysUserMapper.selectById(anyLong())).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(1L, "admin1", "ADMIN", Set.of(1L)));
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);

            assertThat(noticeService.create(dto).getTitle()).isEqualTo("本社区公告");
        }
    }

    /* ---- 通用脚手架 ---- */

    private NoticeTarget target(String type, Long id) {
        NoticeTarget t = new NoticeTarget();
        t.setNoticeId(1L);
        t.setTargetType(type);
        t.setTargetId(id);
        return t;
    }

    private com.community.residence.resident.entity.ResidenceRelation relation(Long residentId, Long communityId) {
        com.community.residence.resident.entity.ResidenceRelation r =
                new com.community.residence.resident.entity.ResidenceRelation();
        r.setResidentId(residentId);
        r.setCommunityId(communityId);
        return r;
    }
}
