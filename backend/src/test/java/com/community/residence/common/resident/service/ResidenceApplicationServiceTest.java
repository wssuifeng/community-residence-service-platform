package com.community.residence.common.resident.service;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.housing.entity.Housing;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.community.constant.HouseStatusConstant;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.dto.ApproveApplicationDTO;
import com.community.residence.common.resident.dto.CreateApplicationDTO;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.community.service.CommunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 入住申请业务逻辑测试：审核状态机、重复在审拒绝、审批联动 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidenceApplicationService 单元测试")
class ResidenceApplicationServiceTest {

    @Mock
    private ResidenceApplicationMapper applicationMapper;
    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
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
    private com.community.residence.common.conversation.service.ConversationService conversationService;
    @Mock
    private NotificationService notificationService;

    @Mock private com.community.residence.common.community.service.HouseStateService houseStateService;

    @InjectMocks
    private ResidenceApplicationService applicationService;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        /* 终态条件更新的 LambdaUpdateWrapper.set 构造期急解析列名，需实体元数据注册
           （OCR DEF-117 适配，口径同 ConversationLeaseGroupEnsureTest） */
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ResidenceApplication.class);
    }

    private House vacantHouse;
    private ResidenceApplication pendingApplication;

    @BeforeEach
    void setUp() {
        com.community.residence.common.housing.entity.Housing source = new com.community.residence.common.housing.entity.Housing();
        source.setId(1L); source.setHouseId(1L); source.setCommunityId(1L); source.setStatus("AVAILABLE"); source.setRentType("RENT"); source.setMonthlyRent(new java.math.BigDecimal("2800"));
        org.mockito.Mockito.lenient().when(housingMapper.selectByIdForUpdate(1L)).thenReturn(source);

        vacantHouse = new House();
        vacantHouse.setId(1L);
        vacantHouse.setUnitId(1L);
        vacantHouse.setCommunityId(1L);
        vacantHouse.setHouseNumber("101");
        vacantHouse.setStatus(HouseStatusConstant.VACANT);

        pendingApplication = new ResidenceApplication();
        pendingApplication.setId(1L);
        pendingApplication.setResidentId(1L);
        pendingApplication.setCommunityId(1L);
        pendingApplication.setHouseId(1L);
        pendingApplication.setRelationType("TENANT");
        pendingApplication.setStatus("PENDING");
    }

    @Test
    @DisplayName("提交申请：社区开启自动通过时提交即生成「待签约」租约并建租约群聊")
    void create_autoApproveCommunity_createsLeaseImmediately() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(0L);
            when(communityService.requireCommunity(1L)).thenReturn(autoApproveCommunity());
            Housing listing = new Housing();
            listing.setId(9L);
            listing.setHouseId(1L);
            listing.setMonthlyRent(new BigDecimal("2500.00"));
            listing.setDeposit(new BigDecimal("5000.00"));
            when(housingMapper.selectByIdForUpdate(1L)).thenReturn(listing);
            listing.setId(1L); listing.setStatus("AVAILABLE");
            when(conversationService.createLeaseGroup(any(), any(), any(), any()))
                    .thenReturn(leaseGroupConversation());

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            applicationService.create(dto);

            /* 租约按社区默认租期（3 个月）生成，租金/押金取房源挂牌值；
               R66 v1.8：审核通过只到待签约（ACTIVE 由协议双方确认联动） */
            ArgumentCaptor<LeaseRecord> leaseCaptor = ArgumentCaptor.forClass(LeaseRecord.class);
            verify(leaseRecordMapper).insert(leaseCaptor.capture());
            LeaseRecord lease = leaseCaptor.getValue();
            assertThat(lease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(lease.getMonthlyRent()).isEqualByComparingTo("2500.00");
            assertThat(lease.getDeposit()).isEqualByComparingTo("5000.00");
            assertThat(lease.getStartDate()).isEqualTo(LocalDate.now());
            assertThat(lease.getEndDate()).isEqualTo(LocalDate.now().plusMonths(3).minusDays(1));

            /* 居住关系建立、房屋**保持空置**（不再翻转 OCCUPIED）、申请落 APPROVED */
            verify(relationMapper, never()).insert(any(ResidenceRelation.class));
            assertThat(vacantHouse.getStatus()).isEqualTo(HouseStatusConstant.VACANT);
            ArgumentCaptor<ResidenceApplication> appCaptor = ArgumentCaptor.forClass(ResidenceApplication.class);
            verify(applicationMapper).updateById(appCaptor.capture());
            assertThat(appCaptor.getValue().getStatus()).isEqualTo("APPROVED");
            assertThat(appCaptor.getValue().getReviewerId()).isNull();

            /* R68 租约群聊：审核通过即建群并回写 conversation_id */
            verify(conversationService).createLeaseGroup(eq(lease.getId()), eq(1L), eq(1L), anyString());
            verify(leaseRecordMapper).updateById(leaseCaptor.capture());
            assertThat(leaseCaptor.getValue().getConversationId()).isEqualTo(77L);
        }
    }

    private com.community.residence.common.conversation.entity.Conversation leaseGroupConversation() {
        com.community.residence.common.conversation.entity.Conversation conversation =
                new com.community.residence.common.conversation.entity.Conversation();
        conversation.setId(77L);
        conversation.setType("LEASE_GROUP");
        conversation.setStatus("ACTIVE");
        return conversation;
    }

    @Test
    @DisplayName("提交申请：社区未开启自动通过时保持待审核（不生成租约）")
    void create_manualApproveCommunity_staysPending() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(0L);
            when(communityService.requireCommunity(1L)).thenReturn(manualCommunity());

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            applicationService.create(dto);

            verify(leaseRecordMapper, never()).insert(any(LeaseRecord.class));
            verify(relationMapper, never()).insert(any(ResidenceRelation.class));
            assertThat(vacantHouse.getStatus()).isEqualTo(HouseStatusConstant.VACANT);
        }
    }

    private Community autoApproveCommunity() {
        Community community = new Community();
        community.setId(1L);
        community.setAutoApproveResidence(1);
        community.setDefaultLeaseMonths(3);
        return community;
    }

    private Community manualCommunity() {
        Community community = new Community();
        community.setId(1L);
        community.setAutoApproveResidence(0);
        community.setDefaultLeaseMonths(12);
        return community;
    }

    @Test
    @DisplayName("提交申请：非空置房屋拒绝（HOUSE_NOT_VACANT）")
    void create_occupiedHouse_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            House occupied = new House();
            occupied.setId(1L);
            occupied.setCommunityId(1L);
            occupied.setStatus(HouseStatusConstant.OCCUPIED);
            when(houseMapper.selectById(1L)).thenReturn(occupied);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.HOUSE_NOT_VACANT));
        }
    }

    @Test
    @DisplayName("提交申请：同一房屋已有在审申请拒绝（APPLICATION_PENDING_EXISTS）")
    void create_duplicatePending_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(1L);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_PENDING_EXISTS));
        }
    }

    @Test
    @DisplayName("审批通过：状态 APPROVED + 租约落待签约 + 房屋保持 VACANT（R66 v1.8 新口径）")
    void approve_tenant_flipsHouseAndCreatesLease() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(2L);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.update(isNull(), any())).thenReturn(1);
            when(applicationMapper.updateById(any(ResidenceApplication.class))).thenReturn(1);

            when(leaseRecordMapper.insert(any(com.community.residence.common.lease.entity.LeaseRecord.class))).thenReturn(1);
            when(conversationService.createLeaseGroup(any(), any(), any(), any()))
                    .thenReturn(leaseGroupConversation());

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.of(2026, 9, 10));
            dto.setLeaseEndDate(LocalDate.of(2027, 9, 9));
            dto.setMonthlyRent(BigDecimal.valueOf(3500));

            var vo = applicationService.approve(1L, dto);

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            /* 审核通过不翻房态：房屋在签约生效前仍为空置 */
            assertThat(vacantHouse.getStatus()).isEqualTo(HouseStatusConstant.VACANT);
            ArgumentCaptor<LeaseRecord> leaseCaptor = ArgumentCaptor.forClass(LeaseRecord.class);
            verify(leaseRecordMapper).insert(leaseCaptor.capture());
            assertThat(leaseCaptor.getValue().getStatus()).isEqualTo("TO_SIGN");
            verify(conversationService).createLeaseGroup(any(), eq(1L), eq(1L), anyString());
            /* 身份空间断言（DEF-067）：审批结果触达申请人居民，标注 createForResident */
            verify(notificationService).createForResident(eq(1L), eq(1L), eq("入住申请已通过"),
                    anyString(), eq("LEASE"), eq("LEASE"), any());
        }
    }

    @Test
    @DisplayName("撤回申请：本人待审核申请 → WITHDRAWN")
    void withdraw_pendingApplication_ok() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(applicationMapper.update(isNull(), any())).thenReturn(1);
            when(leaseRecordMapper.selectOne(any())).thenReturn(null);

            var vo = applicationService.withdraw(1L);

            assertThat(vo.getStatus()).isEqualTo("WITHDRAWN");
            ArgumentCaptor<ResidenceApplication> captor =
                    ArgumentCaptor.forClass(ResidenceApplication.class);
            verify(applicationMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("WITHDRAWN");
        }
    }

    @Test
    @DisplayName("撤回申请：非待审核状态拒绝（APPLICATION_NOT_WITHDRAWABLE 5605）")
    void withdraw_reviewedApplication_rejected() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            pendingApplication.setStatus("APPROVED");
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);

            assertThatThrownBy(() -> applicationService.withdraw(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_NOT_WITHDRAWABLE));
        }
    }

    @Test
    @DisplayName("撤回申请：非本人拒绝（ForbiddenException）")
    void withdraw_othersApplication_forbidden() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(999L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);

            assertThatThrownBy(() -> applicationService.withdraw(1L))
                    .isInstanceOf(com.community.residence.common.web.exception.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("审批状态机：已通过申请重复审批拒绝（APPLICATION_ALREADY_REVIEWED）")
    void approve_alreadyReviewed_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            pendingApplication.setStatus("APPROVED");
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.now());
            dto.setLeaseEndDate(LocalDate.now().plusDays(30));
            dto.setMonthlyRent(BigDecimal.ONE);

            assertThatThrownBy(() -> applicationService.approve(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_ALREADY_REVIEWED));
        }
    }

    @Test
    @DisplayName("撤回并发竞态（OCR DEF-117）：条件更新 0 行 + 已 WITHDRAWN → 幂等返回，不再二次落库/关群")
    void withdraw_concurrentWinnerAlreadyWithdrawn_idempotent() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            ResidenceApplication withdrawn = approvedApplication();
            withdrawn.setStatus("WITHDRAWN");
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication, withdrawn);
            when(applicationMapper.update(isNull(), any())).thenReturn(0);

            var vo = applicationService.withdraw(1L);

            assertThat(vo.getStatus()).isEqualTo("WITHDRAWN");
            verify(applicationMapper, never()).updateById(any(ResidenceApplication.class));
            verify(conversationService, never()).closeLeaseGroup(any());
        }
    }

    @Test
    @DisplayName("撤回并发竞态（OCR DEF-117）：条件更新 0 行 + 已 APPROVED → 5605 拒绝")
    void withdraw_concurrentReviewed_throwsNotWithdrawable() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication, approvedApplication());
            when(applicationMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> applicationService.withdraw(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_NOT_WITHDRAWABLE));
            verify(conversationService, never()).closeLeaseGroup(any());
        }
    }

    @Test
    @DisplayName("撤回弱回退收窄（OCR DEF-117）：弱命中 TO_SIGN 活跃租约 → 跳过关群（活跃租约的群是现行沟通渠道）")
    void withdraw_weakFallbackActiveLease_skipsGroupClose() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(applicationMapper.update(isNull(), any())).thenReturn(1);
            LeaseRecord liveLease = new LeaseRecord();
            liveLease.setId(7L);
            liveLease.setStatus("TO_SIGN");
            when(leaseRecordMapper.selectOne(any())).thenReturn(liveLease);

            var vo = applicationService.withdraw(1L);

            assertThat(vo.getStatus()).isEqualTo("WITHDRAWN");
            verify(conversationService, never()).closeLeaseGroup(any());
        }
    }

    @Test
    @DisplayName("撤回强关联（OCR DEF-117 对照）：lease_id 直挂本申请的租约即便 TO_SIGN 也照常关群")
    void withdraw_strongLinkLease_closesGroupRegardlessOfActive() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> com.community.residence.common.security.context.SecurityUtils
                    .hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT)).thenReturn(true);
            pendingApplication.setLeaseId(3L);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(applicationMapper.update(isNull(), any())).thenReturn(1);
            LeaseRecord linkedLease = new LeaseRecord();
            linkedLease.setId(3L);
            linkedLease.setStatus("TO_SIGN");
            when(leaseRecordMapper.selectById(3L)).thenReturn(linkedLease);

            applicationService.withdraw(1L);

            verify(conversationService).closeLeaseGroup(3L);
        }
    }

    @Test
    @DisplayName("审批并发竞态（OCR DEF-117）：条件更新 0 行 + 已 APPROVED → 幂等返回，不重建关系/租约/群")
    void approve_concurrentWinnerAlreadyApproved_idempotent() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(2L);
            ResidenceApplication approved = approvedApplication();
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication, approved);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.update(isNull(), any())).thenReturn(0);

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.now());
            dto.setLeaseEndDate(LocalDate.now().plusDays(30));
            dto.setMonthlyRent(BigDecimal.ONE);

            var vo = applicationService.approve(1L, dto);

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            verify(relationMapper, never()).insert(any(ResidenceRelation.class));
            verify(leaseRecordMapper, never()).insert(any(LeaseRecord.class));
            verify(conversationService, never()).createLeaseGroup(any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("提交申请：房屋已有进行中租约拒绝重复申请")
    void create_existingLease_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(0L);
            when(leaseRecordMapper.selectCount(any())).thenReturn(1L);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("进行中的租约");
            verify(applicationMapper, never()).insert(any(ResidenceApplication.class));
        }
    }

    @Test
    @DisplayName("提交申请：房屋已有其他待审核申请拒绝第二个申请")
    void create_otherPendingApplication_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(2L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(0L, 1L);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setHousingId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("待处理入住申请");
            verify(applicationMapper, never()).insert(any(ResidenceApplication.class));
        }
    }

    @Test
    @DisplayName("审批通过：已有进行中租约时不再创建居住关系、租约或群聊")
    void approve_existingLease_throwsBeforeSideEffects() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(2L);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(leaseRecordMapper.selectCount(any())).thenReturn(1L);

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.now());
            dto.setLeaseEndDate(LocalDate.now().plusMonths(12));
            dto.setMonthlyRent(BigDecimal.valueOf(3000));

            assertThatThrownBy(() -> applicationService.approve(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("进行中的租约");
            verify(relationMapper, never()).insert(any(ResidenceRelation.class));
            verify(leaseRecordMapper, never()).insert(any(LeaseRecord.class));
            verify(conversationService, never()).createLeaseGroup(any(), any(), any(), any());
        }
    }

    private ResidenceApplication approvedApplication() {
        ResidenceApplication approved = new ResidenceApplication();
        approved.setId(1L);
        approved.setResidentId(1L);
        approved.setCommunityId(1L);
        approved.setHouseId(1L);
        approved.setRelationType("TENANT");
        approved.setStatus("APPROVED");
        return approved;
    }
    @org.mockito.Mock
    private com.community.residence.common.community.service.CommunityResponsibleService communityResponsibleService;

    @Test
    void explicitSourcePriceIsFrozenAtSubmission() {
        Community community = new Community(); community.setId(1L); community.setAutoApproveResidence(0);
        when(communityService.requireCommunity(1L)).thenReturn(community);
        when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
        var source = new Housing(); source.setId(88L); source.setHouseId(1L); source.setCommunityId(1L);
        source.setStatus("AVAILABLE"); source.setRentType("RENT"); source.setMonthlyRent(new BigDecimal("3500")); source.setDeposit(new BigDecimal("7000"));
        when(housingMapper.selectByIdForUpdate(88L)).thenReturn(source);
        CreateApplicationDTO dto = new CreateApplicationDTO(); dto.setHouseId(1L); dto.setHousingId(88L); dto.setRelationType("TENANT");
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(1L);
            var result = applicationService.create(dto);
            source.setMonthlyRent(new BigDecimal("9999"));
            assertThat(result.getHousingId()).isEqualTo(88L);
            assertThat(result.getListedMonthlyRent()).isEqualByComparingTo("3500");
            assertThat(result.getListedDeposit()).isEqualByComparingTo("7000");
            verify(housingMapper, never()).selectOne(any());
            verify(relationMapper, never()).insert(any(ResidenceRelation.class));
        }
    }

    @Test
    void tenantMissingSourceIsRejectedWithoutInsert() {
        when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
        CreateApplicationDTO dto = new CreateApplicationDTO(); dto.setHouseId(1L); dto.setRelationType("TENANT");
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(1L);
            assertThatThrownBy(() -> applicationService.create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("来源挂牌");
            verify(applicationMapper, never()).insert(any(ResidenceApplication.class));
        }
    }

    @Test
    void wrongHouseOrSaleSourceIsRejected() {
        when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
        var source = new Housing(); source.setId(88L); source.setHouseId(99L); source.setStatus("AVAILABLE"); source.setRentType("RENT");
        when(housingMapper.selectByIdForUpdate(88L)).thenReturn(source);
        CreateApplicationDTO dto = new CreateApplicationDTO(); dto.setHouseId(1L); dto.setHousingId(88L); dto.setRelationType("TENANT");
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(1L);
            assertThatThrownBy(() -> applicationService.create(dto)).isInstanceOf(BusinessException.class);
            source.setHouseId(1L); source.setRentType("SALE");
            assertThatThrownBy(() -> applicationService.create(dto)).isInstanceOf(BusinessException.class);
            verify(applicationMapper, never()).insert(any(ResidenceApplication.class));
        }
    }
    @Test void monthEndApprovalPreservesThirteenMonthsAndThreeMonthFirstPayment() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(2L);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.update(isNull(), any())).thenReturn(1);
            var dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.of(2027, 1, 31));
            dto.setContractMonths(13);dto.setFirstPaymentMonths(3);dto.setPaymentCycleMonths(1);dto.setMonthlyRent(BigDecimal.valueOf(3500));
            applicationService.approve(1L,dto);
            var lease = ArgumentCaptor.forClass(LeaseRecord.class);verify(leaseRecordMapper).insert(lease.capture());
            assertThat(lease.getValue().getContractMonths()).isEqualTo(13);
            assertThat(lease.getValue().getEndDate()).isEqualTo(LocalDate.of(2028,2,28));
            assertThat(lease.getValue().getFirstPaymentMonths()).isEqualTo(3);
        }
    }
}
