package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.notice.vo.NoticeVO;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公告多目标定向与置顶测试（R24/R25 v1.2）：多目标创建、越范围逐项拒绝、置顶 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeService 多目标定向与置顶单元测试")
class NoticeTargetPinTest {

    @org.junit.jupiter.api.BeforeAll
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
    private CommunityService communityService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private com.community.residence.resident.mapper.ResidentMapper residentMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private BuildingMapper buildingMapper;

    @InjectMocks
    private NoticeService noticeService;

    private CreateNoticeDTO.TargetItemDTO community(Long id) {
        CreateNoticeDTO.TargetItemDTO t = new CreateNoticeDTO.TargetItemDTO();
        t.setTargetType("COMMUNITY");
        t.setTargetId(id);
        return t;
    }

    private CreateNoticeDTO.TargetItemDTO building(Long id) {
        CreateNoticeDTO.TargetItemDTO t = new CreateNoticeDTO.TargetItemDTO();
        t.setTargetType("BUILDING");
        t.setTargetId(id);
        return t;
    }

    private CreateNoticeDTO dto(List<CreateNoticeDTO.TargetItemDTO> targets, Integer isPinned) {
        CreateNoticeDTO dto = new CreateNoticeDTO();
        dto.setTargets(targets);
        dto.setIsPinned(isPinned);
        dto.setTitle("多目标公告");
        dto.setContent("内容");
        dto.setPublishTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        return dto;
    }

    private MockedStatic<SecurityUtils> mockSuperAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(1L);
        /* checkCommunityAccess 为 void 静态方法，mockStatic 默认 no-op（放行），
           无需显式 stub；越权用例单独 thenThrow */
        return mocked;
    }

    @Test
    @DisplayName("多目标创建：2 社区 + 1 楼栋 → notice_target 写 3 条 + isPinned 落值")
    void create_multipleTargets_writesAllTargets() {
        when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notice.class).setId(8L);
            return 1;
        });
        when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);
        Building b = new Building();
        b.setId(20L);
        b.setCommunityId(1L);
        when(buildingMapper.selectById(20L)).thenReturn(b);

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            NoticeVO vo = noticeService.create(dto(
                    List.of(community(1L), community(2L), building(20L)), 1));

            assertThat(vo.getIsPinned()).isEqualTo(1);
        }
        verify(noticeTargetMapper, times(3)).insert(any(NoticeTarget.class));
        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsPinned()).isEqualTo(1);
    }

    @Test
    @DisplayName("越范围目标逐项拒绝：ADMIN 目标社区含绑定外社区 → 403")
    void create_outOfBoundTarget_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            mocked.when(() -> SecurityUtils.checkCommunityAccess(99L))
                    .thenThrow(new ForbiddenException("无权操作未绑定社区的数据"));

            assertThatThrownBy(() -> noticeService.create(
                    dto(List.of(community(1L), community(99L)), null)))
                    .isInstanceOf(ForbiddenException.class);
        }
        verify(noticeMapper, times(0)).insert(any(Notice.class));
    }

    @Test
    @DisplayName("非法目标类型：400 拒绝")
    void create_invalidTargetType_rejected() {
        CreateNoticeDTO.TargetItemDTO bad = new CreateNoticeDTO.TargetItemDTO();
        bad.setTargetType("UNIT");
        bad.setTargetId(1L);
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            assertThatThrownBy(() -> noticeService.create(dto(List.of(bad), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法目标类型");
        }
    }

    @Test
    @DisplayName("BUILDING 目标不存在：400 拒绝")
    void create_missingBuilding_rejected() {
        when(buildingMapper.selectById(404L)).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            assertThatThrownBy(() -> noticeService.create(dto(List.of(building(404L)), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("目标楼栋不存在");
        }
    }

    @Test
    @DisplayName("向后兼容：仅传 communityId 单目标写法仍生效（写 1 条 COMMUNITY 目标）")
    void create_legacyCommunityId_stillWorks() {
        when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
            inv.getArgument(0, Notice.class).setId(9L);
            return 1;
        });
        when(noticeTargetMapper.insert(any(NoticeTarget.class))).thenReturn(1);

        CreateNoticeDTO legacy = new CreateNoticeDTO();
        legacy.setCommunityId(1L);
        legacy.setTitle("旧写法");
        legacy.setContent("c");
        legacy.setPublishTime(LocalDateTime.of(2026, 10, 1, 9, 0));

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            noticeService.create(legacy);
        }
        ArgumentCaptor<NoticeTarget> captor = ArgumentCaptor.forClass(NoticeTarget.class);
        verify(noticeTargetMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo("COMMUNITY");
        assertThat(captor.getValue().getTargetId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("置顶排序：列表 orderBy is_pinned DESC, publish_time DESC（wrapper 断言）")
    void page_ordersPinnedFirst() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
            when(noticeMapper.selectPage(any(), any())).thenAnswer(inv -> {
                com.baomidou.mybatisplus.extension.plugins.pagination.Page<Notice> p =
                        inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            noticeService.page(1, 10, null, null);

            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notice>>
                    wrapperCaptor = ArgumentCaptor.forClass(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(noticeMapper).selectPage(any(), wrapperCaptor.capture());
            String sql = wrapperCaptor.getValue().getSqlSegment();
            assertThat(sql).contains("is_pinned DESC");
            assertThat(sql).contains("publish_time DESC");
        }
    }
}
