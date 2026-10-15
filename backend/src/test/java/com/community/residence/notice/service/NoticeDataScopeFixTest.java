package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
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
import com.community.residence.resident.entity.ResidenceRelation;
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
import static org.mockito.Mockito.verify;
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
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;

    @InjectMocks
    private NoticeService noticeService;

    private Notice publishedNotice;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(noticeMapper.updateLifecycle(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
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

    /* ---- DEF-162：BUILDING 目标必须沿当前居住关系命中具体楼栋 ---- */

    @Test
    @DisplayName("DEF-162：居民位于 A-1，BUILDING=A-2 公告详情不可见")
    void getById_residentInOtherBuilding_notVisible() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("BUILDING", 102L)));
        stubResidentScope(5L, 1L, 501L, 101L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mockResident(mocked, 5L);
            assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("公告不存在");
        }
    }

    @Test
    @DisplayName("DEF-162：居民位于 A-1，BUILDING=A-1 公告详情可见")
    void getById_residentInSameBuilding_visible() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("BUILDING", 101L)));
        lenient().when(buildingMapper.selectById(101L)).thenReturn(building(101L, 1L));
        lenient().when(sysUserMapper.selectById(anyLong())).thenReturn(null);
        stubResidentScope(5L, 1L, 501L, 101L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mockResident(mocked, 5L);
            assertThat(noticeService.getById(1L).getTitle()).isEqualTo("清源里定向公告");
        }
    }

    @Test
    @DisplayName("DEF-162：纯 GUEST 目标公告不对居民开放详情")
    void getById_residentGuestOnly_notVisible() {
        lenient().when(noticeMapper.selectById(1L)).thenReturn(publishedNotice);
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("GUEST", 0L)));
        lenient().when(noticeTargetMapper.selectCount(any())).thenReturn(1L);
        lenient().when(residenceRelationMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mockResident(mocked, 5L);
            assertThatThrownBy(() -> noticeService.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("公告不存在");
        }
    }

    @Test
    @DisplayName("DEF-162：管理员绑定社区可看到该社区楼栋定向公告")
    void page_adminBoundCommunity_seesBuildingNotice() {
        lenient().when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target("BUILDING", 101L)));
        lenient().when(buildingMapper.selectBatchIds(any())).thenReturn(List.of(building(101L, 1L)));
        lenient().when(noticeMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Notice> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
            mocked.when(SecurityUtils::getCommunityIds).thenReturn(Set.of(1L));
            noticeService.page(1, 10, null, null, null, null, null);
            verify(noticeMapper).selectPage(any(), any());
        }
    }

    @Test
    @DisplayName("DEF-162：发布楼栋定向公告只触达目标楼栋在住居民")
    void publish_buildingTarget_notifiesOnlyResidentsInBuilding() {
        Notice draft = new Notice();
        draft.setId(2L);
        draft.setTitle("楼栋公告");
        draft.setStatus("DRAFT");
        draft.setPublisherId(1L);
        when(noticeMapper.selectById(2L)).thenReturn(draft);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target(2L, "BUILDING", 101L)));
        when(residenceRelationMapper.selectList(any())).thenReturn(List.of(
                relation(5L, 1L, 501L), relation(6L, 1L, 502L)));
        when(houseMapper.selectBatchIds(any())).thenReturn(List.of(
                house(501L, 601L), house(502L, 602L)));
        when(unitMapper.selectBatchIds(any())).thenReturn(List.of(
                unit(601L, 101L), unit(602L, 102L)));
        Resident targetResident = new Resident();
        targetResident.setId(5L);
        targetResident.setStatus("ACTIVE");
        when(residentMapper.selectBatchIds(any())).thenReturn(List.of(targetResident));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            noticeService.publish(2L, LocalDateTime.now());
            verify(notificationService).createForResident(5L, null, "社区公告",
                    "新公告「楼栋公告」已发布，请查看", "NOTICE", "NOTICE", 2L);
        }
    }

    private void mockResident(MockedStatic<SecurityUtils> mocked, Long residentId) {
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(
                residentId, "resident", "RESIDENT", Set.of()));
        mocked.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
        mocked.when(SecurityUtils::getUserId).thenReturn(residentId);
    }

    private void stubResidentScope(Long residentId, Long communityId, Long houseId, Long buildingId) {
        when(residenceRelationMapper.selectList(any())).thenReturn(List.of(
                relation(residentId, communityId, houseId)));
        when(houseMapper.selectBatchIds(any())).thenReturn(List.of(house(houseId, 601L)));
        when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit(601L, buildingId)));
    }

    /* ---- 通用脚手架 ---- */

    private NoticeTarget target(String type, Long id) {
        return target(1L, type, id);
    }

    private NoticeTarget target(Long noticeId, String type, Long id) {
        NoticeTarget t = new NoticeTarget();
        t.setNoticeId(noticeId);
        t.setTargetType(type);
        t.setTargetId(id);
        return t;
    }

    private ResidenceRelation relation(Long residentId, Long communityId) {
        return relation(residentId, communityId, null);
    }

    private ResidenceRelation relation(Long residentId, Long communityId, Long houseId) {
        ResidenceRelation r = new ResidenceRelation();
        r.setResidentId(residentId);
        r.setCommunityId(communityId);
        r.setHouseId(houseId);
        r.setMoveOutDate(null);
        return r;
    }

    private House house(Long id, Long unitId) {
        House h = new House();
        h.setId(id);
        h.setUnitId(unitId);
        return h;
    }

    private Unit unit(Long id, Long buildingId) {
        Unit u = new Unit();
        u.setId(id);
        u.setBuildingId(buildingId);
        return u;
    }

    private Building building(Long id, Long communityId) {
        Building b = new Building();
        b.setId(id);
        b.setCommunityId(communityId);
        return b;
    }
    @org.mockito.Mock
    private com.community.residence.community.service.CommunityResponsibleService communityResponsibleService;
}
