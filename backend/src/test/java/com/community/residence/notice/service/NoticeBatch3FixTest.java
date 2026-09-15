package com.community.residence.notice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.community.entity.Building;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.service.CommunityService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第三批修复回归：DEF-031（公告 priority/isPinned 过滤）+ C3 项（表单字段补全） */
@ExtendWith(MockitoExtension.class)
@DisplayName("公告第三批修复回归（DEF-031/C3）")
class NoticeBatch3FixTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Notice.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, NoticeTarget.class);
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

    /* ---- C3 项：priority/type/expireTime 接收 ---- */

    @Test
    @DisplayName("C3：创建接收 priority/type/expireTime——expireTime 映射 endTime 列，字段落库")
    void create_receivesFormFields() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);
            when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
                inv.getArgument(0, Notice.class).setId(1L);
                return 1;
            });

            var dto = baseDto();
            dto.setPriority("URGENT");
            dto.setType("ANNOUNCEMENT");
            dto.setExpireTime(LocalDateTime.of(2026, 10, 1, 0, 0));
            NoticeVO vo = noticeService.create(dto);

            ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
            verify(noticeMapper).insert(captor.capture());
            assertThat(captor.getValue().getPriority()).isEqualTo("URGENT");
            assertThat(captor.getValue().getType()).isEqualTo("ANNOUNCEMENT");
            assertThat(captor.getValue().getEndTime())
                    .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
            /* VO 暴露新字段（expireTime 同值别名） */
            assertThat(vo.getPriority()).isEqualTo("URGENT");
            assertThat(vo.getType()).isEqualTo("ANNOUNCEMENT");
            assertThat(vo.getExpireTime()).isEqualTo(vo.getEndTime());
        }
    }

    @Test
    @DisplayName("C3：priority/type 缺省落 NORMAL/ANNOUNCEMENT；endTime 缺省 +30 天")
    void create_defaultsApplied() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);
            when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
                inv.getArgument(0, Notice.class).setId(2L);
                return 1;
            });

            noticeService.create(baseDto());

            ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
            verify(noticeMapper).insert(captor.capture());
            assertThat(captor.getValue().getPriority()).isEqualTo("NORMAL");
            assertThat(captor.getValue().getType()).isEqualTo("ANNOUNCEMENT");
            assertThat(captor.getValue().getEndTime())
                    .isEqualTo(baseDto().getPublishTime().plusDays(30));
        }
    }

    @Test
    @DisplayName("C3：endTime 与 expireTime 并设时以 endTime 为准（显式字段优先）")
    void create_endTimeTakesPrecedence() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);
            when(noticeMapper.insert(any(Notice.class))).thenAnswer(inv -> {
                inv.getArgument(0, Notice.class).setId(3L);
                return 1;
            });

            var dto = baseDto();
            dto.setEndTime(LocalDateTime.of(2026, 9, 20, 0, 0));
            dto.setExpireTime(LocalDateTime.of(2026, 12, 31, 0, 0));
            noticeService.create(dto);

            ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
            verify(noticeMapper).insert(captor.capture());
            assertThat(captor.getValue().getEndTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 20, 0, 0));
        }
    }

    /* ---- DEF-031：priority/isPinned 过滤参数 ---- */

    @Test
    @DisplayName("DEF-031：page 接收 priority/isPinned/type 过滤参数并注入查询条件")
    void page_acceptsFilterParams() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);
            when(noticeMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<Notice> p = inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            var vo = noticeService.page(1, 10, null, null, "HIGH", 1, "ANNOUNCEMENT");
            assertThat(vo.getTotal()).isEqualTo(0);

            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notice>>
                    wrapperCaptor = ArgumentCaptor.forClass(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(noticeMapper).selectPage(any(), wrapperCaptor.capture());
            String sql = wrapperCaptor.getValue().getTargetSql();
            assertThat(sql).contains("priority");
            assertThat(sql).contains("is_pinned");
            assertThat(sql).contains("type");
        }
    }

    /* ---- 脚手架 ---- */

    @Test
    @DisplayName("DEF-043：update 重写 targets——先删后插，编辑定向生效")
    void update_rewritesTargets_deleteThenInsert() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);

            Notice draft = new Notice();
            draft.setId(9L);
            draft.setStatus("DRAFT");
            draft.setPublisherId(1L);
            when(noticeMapper.selectById(9L)).thenReturn(draft);
            when(communityService.requireActiveCommunity(any())).thenReturn(null);

            var dto = baseDto();
            var t1 = new com.community.residence.notice.dto.CreateNoticeDTO.TargetItemDTO();
            t1.setTargetType("COMMUNITY");
            t1.setTargetId(1L);
            var t2 = new com.community.residence.notice.dto.CreateNoticeDTO.TargetItemDTO();
            t2.setTargetType("COMMUNITY");
            t2.setTargetId(2L);
            dto.setTargets(List.of(t1, t2));

            noticeService.update(9L, dto);

            /* 先删后插：删旧目标行一次，插入两条新目标 */
            verify(noticeTargetMapper).delete(any());
            ArgumentCaptor<NoticeTarget> captor = ArgumentCaptor.forClass(NoticeTarget.class);
            verify(noticeTargetMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(NoticeTarget::getTargetId)
                    .containsExactlyInAnyOrder(1L, 2L);
        }
    }

    @Test
    @DisplayName("DEF-043：超管编辑为广播（targets 空）时清空目标行")
    void update_emptyTargets_clearsRows() {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("SUPER_ADMIN")).thenReturn(true);

            Notice draft = new Notice();
            draft.setId(9L);
            draft.setStatus("DRAFT");
            draft.setPublisherId(1L);
            when(noticeMapper.selectById(9L)).thenReturn(draft);

            noticeService.update(9L, baseDto());

            verify(noticeTargetMapper).delete(any());
            verify(noticeTargetMapper, org.mockito.Mockito.never()).insert(any(NoticeTarget.class));
        }
    }

    private CreateNoticeDTO baseDto() {
        CreateNoticeDTO dto = new CreateNoticeDTO();
        dto.setTitle("测试公告");
        dto.setContent("内容");
        dto.setPublishTime(LocalDateTime.of(2026, 9, 14, 12, 0));
        return dto;
    }
}
