package com.community.residence.common.resident.service;

import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.dto.ApproveApplicationDTO;
import com.community.residence.common.resident.dto.CreateApplicationDTO;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入住申请 `lease_id` 写回回归（V26 / DEF-080）：
 * ①**常规审批**（`approve`）通过后，申请上的 `lease_id` = 本次新建租约 id；
 * ②**社区自动审批**（`community.auto_approve_residence=1`，与人工审批共用 `applyApproval` 内核）
 *   同样写回——两条链路都必须覆盖；
 * ③**撤回**路径：强关联优先，缺失时允许弱关联兜底并关闭群聊（仅该副作用路径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("入住申请 lease_id 写回（V26/DEF-080）")
class ResidenceApplicationLeaseLinkTest {

    private static final Long APPLICATION_ID = 11L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long COMMUNITY_ID = 1L;
    private static final Long HOUSE_ID = 12L;
    private static final Long ADMIN_ID = 2L;
    private static final Long NEW_LEASE_ID = 101L;
    private static final Long EXISTING_LEASE_ID = 88L;
    private static final Long CONVERSATION_ID = 55L;

    @Mock
    private ResidenceApplicationMapper applicationMapper;
    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private com.community.residence.common.community.service.HouseStateService houseStateService;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private com.community.residence.common.housing.mapper.ViewingAppointmentMapper viewingAppointmentMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private CommunityService communityService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ResidenceApplicationService applicationService;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        /* 终态条件更新的 LambdaUpdateWrapper.set 构造期解析列名，需实体元数据注册
           （OCR DEF-117 适配，不依赖其他测试类的注册副作用） */
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ResidenceApplication.class);
    }

    private ResidenceApplication application;
    private House house;
    private final AtomicLong leaseIdSeq = new AtomicLong(NEW_LEASE_ID);

    @BeforeEach
    void setUp() {
        com.community.residence.common.housing.entity.Housing source = new com.community.residence.common.housing.entity.Housing();
        source.setId(1L); source.setHouseId(HOUSE_ID); source.setCommunityId(1L); source.setStatus("AVAILABLE"); source.setRentType("RENT"); source.setMonthlyRent(new java.math.BigDecimal("2800"));
        org.mockito.Mockito.lenient().when(housingMapper.selectByIdForUpdate(1L)).thenReturn(source);

        application = new ResidenceApplication();
        application.setId(APPLICATION_ID);
        application.setResidentId(RESIDENT_ID);
        application.setCommunityId(COMMUNITY_ID);
        application.setHouseId(HOUSE_ID);
        application.setRelationType("TENANT");
        application.setStatus("PENDING");
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(application);
        /* 终态抢占条件更新（OCR DEF-117）：mock 默认返回 0 会误入并发分支，统一桩为抢占成功 */
        when(applicationMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        house = new House();
        house.setId(HOUSE_ID);
        house.setHouseNumber("101");
        house.setCommunityId(COMMUNITY_ID);
        house.setStatus("VACANT");
        when(houseMapper.selectById(HOUSE_ID)).thenReturn(house);
        when(unitMapper.selectById(any())).thenReturn(null);

        /* 新增租约：MP 的 insert 回填自增主键，此处显式赋 id 模拟 */
        when(leaseRecordMapper.insert(any(LeaseRecord.class))).thenAnswer(inv -> {
            LeaseRecord lease = inv.getArgument(0);
            lease.setId(leaseIdSeq.getAndIncrement());
            return 1;
        });
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        when(conversationService.createLeaseGroup(any(), any(), any(), anyString()))
                .thenReturn(conversation);
    }

    private MockedStatic<SecurityUtils> loginAsAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
        mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(true);
        return mocked;
    }

    private ApproveApplicationDTO approveDto() {
        ApproveApplicationDTO dto = new ApproveApplicationDTO();
        dto.setLeaseStartDate(LocalDate.now());
        dto.setLeaseEndDate(LocalDate.now().plusMonths(12));
        dto.setMonthlyRent(new BigDecimal("2800"));
        dto.setDeposit(new BigDecimal("2800"));
        dto.setRemark("审核通过");
        return dto;
    }

    @Test
    @DisplayName("①常规审批：lease_id 写回本次新建租约 id（并与 status 同一次 updateById 落库）")
    void approve_writesBackLeaseId() {
        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            applicationService.approve(APPLICATION_ID, approveDto());
        }

        assertThat(application.getStatus()).isEqualTo("APPROVED");
        assertThat(application.getLeaseId()).isEqualTo(NEW_LEASE_ID);
        ArgumentCaptor<ResidenceApplication> captor = ArgumentCaptor.forClass(ResidenceApplication.class);
        verify(applicationMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getLeaseId()).isEqualTo(NEW_LEASE_ID);
    }

    @Test
    @DisplayName("②社区自动审批：同一内核，lease_id 同样写回（旁路覆盖）")
    void autoApprove_writesBackLeaseId() {
        Community community = new Community();
        community.setId(COMMUNITY_ID);
        community.setAutoApproveResidence(1);
        community.setDefaultLeaseMonths(12);
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community);
        when(applicationMapper.selectCount(any())).thenReturn(0L);


        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(HOUSE_ID);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");
            dto.setRemark("申请");

            applicationService.create(dto);
        }

        /* create 内部新建实体并落库（同一实例随后被审批内核改写），故捕获 insert 的入参断言 */
        ArgumentCaptor<ResidenceApplication> captor = ArgumentCaptor.forClass(ResidenceApplication.class);
        verify(applicationMapper).insert(captor.capture());
        ResidenceApplication created = captor.getValue();
        assertThat(created.getStatus()).isEqualTo("APPROVED");
        assertThat(created.getLeaseId()).isEqualTo(NEW_LEASE_ID);
        assertThat(created.getReviewRemark()).contains("自动");
    }

    @Test
    @DisplayName("④看房→租约指向修正：常规审批后按（居民 + 本房屋）把看房预约补写成本次租约")
    void approve_linksViewingsToNewLease() {
        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            applicationService.approve(APPLICATION_ID, approveDto());
        }

        verify(viewingAppointmentMapper).linkToLease(NEW_LEASE_ID, RESIDENT_ID, HOUSE_ID);
    }

    @Test
    @DisplayName("④′同一条指向修正覆盖社区自动审批链路（与人工审批共用内核）")
    void autoApprove_linksViewingsToNewLease() {
        Community community = new Community();
        community.setId(COMMUNITY_ID);
        community.setAutoApproveResidence(1);
        community.setDefaultLeaseMonths(12);
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community);
        when(applicationMapper.selectCount(any())).thenReturn(0L);


        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(HOUSE_ID);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");
            applicationService.create(dto);
        }

        verify(viewingAppointmentMapper).linkToLease(NEW_LEASE_ID, RESIDENT_ID, HOUSE_ID);
    }

    @Test
    @DisplayName("⑤撤回：无强关联时弱关联兜底并关闭该租约群聊（仅副作用路径允许回退）")
    void withdraw_weakFallbackClosesGroup() {
        application.setLeaseId(null);
        LeaseRecord weak = new LeaseRecord();
        weak.setId(EXISTING_LEASE_ID);
        when(leaseRecordMapper.selectOne(any())).thenReturn(weak);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);

            applicationService.withdraw(APPLICATION_ID);
        }

        assertThat(application.getStatus()).isEqualTo("WITHDRAWN");
        /* 撤回保留 lease_id 原值（此处为 NULL），群聊按弱关联定位关闭 */
        assertThat(application.getLeaseId()).isNull();
        verify(conversationService).closeLeaseGroup(EXISTING_LEASE_ID);
    }

    @Test
    @DisplayName("③′撤回：强关联存在时直接用强关联租约关闭群聊，不查弱关联")
    void withdraw_usesStrongLinkFirst() {
        application.setLeaseId(EXISTING_LEASE_ID);
        LeaseRecord linked = new LeaseRecord();
        linked.setId(EXISTING_LEASE_ID);
        when(leaseRecordMapper.selectById(EXISTING_LEASE_ID)).thenReturn(linked);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);

            applicationService.withdraw(APPLICATION_ID);
        }

        verify(conversationService).closeLeaseGroup(EXISTING_LEASE_ID);
        verify(leaseRecordMapper, org.mockito.Mockito.never()).selectOne(any());
        /* 撤回不清空关联（审计线索保留） */
        assertThat(application.getLeaseId()).isEqualTo(EXISTING_LEASE_ID);
    }

    @Test
    @DisplayName("③″撤回：既无强关联也无弱关联 → 不关闭任何群聊且不抛异常")
    void withdraw_noLeaseAtAll_noClose() {
        application.setLeaseId(null);
        when(leaseRecordMapper.selectOne(any())).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);

            applicationService.withdraw(APPLICATION_ID);
        }

        assertThat(application.getStatus()).isEqualTo("WITHDRAWN");
        verify(conversationService, org.mockito.Mockito.never()).closeLeaseGroup(any());
    }
    @org.mockito.Mock
    private com.community.residence.common.community.service.CommunityResponsibleService communityResponsibleService;
}
