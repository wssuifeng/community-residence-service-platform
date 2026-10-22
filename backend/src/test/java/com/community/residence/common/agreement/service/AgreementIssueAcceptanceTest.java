package com.community.residence.common.agreement.service;

import com.community.residence.common.agreement.dto.AgreementConfirmDTO;
import com.community.residence.common.agreement.dto.CreateAgreementDTO;
import com.community.residence.common.agreement.entity.AgreementTemplate;
import com.community.residence.common.agreement.entity.LeaseAgreement;
import com.community.residence.common.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.common.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 协议签署与租约生效联动回归（R64 + R66 v1.8）：
 * ① 发起前置校验 tenant_confirm_at 非空（否则 5601）；
 * ② 双方确认达成 SIGNED 联动租约 TO_SIGN → ACTIVE + 房屋置已入住 +
 *    lease_change_log(STATUS) + 租约群聊系统消息；
 * ③ 首期费用支付成功后才生效时重复联动幂等；④ 越界状态拒绝生效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("租赁协议与租约生效联动（R64/R66）")
class AgreementIssueAcceptanceTest {
    @org.mockito.Mock private com.community.residence.common.lease.service.LeaseContractTermService termService;

    private static final Long LEASE_ID = 8L;
    private static final Long AGREEMENT_ID = 21L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long CONVERSATION_ID = 66L;

    @Mock
    private AgreementTemplateMapper templateMapper;
    @Mock
    private LeaseAgreementMapper agreementMapper;
    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ConversationService conversationService;

    @InjectMocks
    private LeaseService leaseService;

    /* DEF-115：协议生效委托 LeaseService 统一流转内核，@InjectMocks 无法跨对象注入
       真实依赖链，手工构造把真实内核接上（本文件 mock 已覆盖 LeaseService 全部构造参数） */
    private AgreementService agreementService;

    private LeaseRecord toSignLease;
    private LeaseAgreement pendingAgreement;
    private House house;

    @BeforeEach
    void setUp() {
        agreementService = new AgreementService(templateMapper, agreementMapper, leaseMapper,
                communityMapper, houseMapper, unitMapper, buildingMapper, residentMapper,
                sysAdminCommunityMapper, sysUserMapper, notificationService, changeLogService,
                conversationMapper, conversationService, leaseService);
        var termFixture=org.mockito.Mockito.mock(com.community.residence.common.lease.service.LeaseContractTermService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(agreementService,"termService",termFixture);
        org.mockito.Mockito.lenient().when(termFixture.initial(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{var term=new com.community.residence.common.lease.entity.LeaseContractTerm();term.setContentSnapshot("已确认的合同付款安排快照");return term;});


        toSignLease = new LeaseRecord();
        toSignLease.setId(LEASE_ID);
        toSignLease.setTenantId(RESIDENT_ID);
        toSignLease.setCommunityId(3L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setStartDate(LocalDate.now());
        toSignLease.setEndDate(LocalDate.now().plusMonths(12));
        toSignLease.setConversationId(CONVERSATION_ID);

        pendingAgreement = new LeaseAgreement();
        pendingAgreement.setId(AGREEMENT_ID);
        pendingAgreement.setLeaseId(LEASE_ID);
        pendingAgreement.setCommunityId(3L);
        pendingAgreement.setTenantId(RESIDENT_ID);
        pendingAgreement.setTitle("演示小区1栋101 租赁协议");
        pendingAgreement.setStatus("PENDING");
        /* 管理方已确认，待居民确认即达成 SIGNED */
        pendingAgreement.setAdminConfirmTime(LocalDateTime.now().minusMinutes(5));
        pendingAgreement.setAdminConfirmName("王管理");

        house = new House();
        house.setId(5L);
        house.setCommunityId(3L);
        house.setStatus("VACANT");

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);
        when(leaseMapper.selectByIdForUpdate(LEASE_ID)).thenReturn(toSignLease);
        when(agreementMapper.selectById(AGREEMENT_ID)).thenReturn(pendingAgreement);
        when(agreementMapper.selectByIdForUpdate(AGREEMENT_ID)).thenReturn(pendingAgreement);
        when(houseMapper.selectById(5L)).thenReturn(house);
        /* 统一内核条件更新（DEF-115）：正常流转返回 1 行 */
        when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
        Resident resident = new Resident();
        resident.setRealName("张居民");
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
    }

    private MockedStatic<SecurityUtils> actor(boolean resident, Long id) {
        var auth = mockStatic(SecurityUtils.class);
        auth.when(SecurityUtils::getUserId).thenReturn(id);
        auth.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(resident);
        auth.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(!resident);
        return auth;
    }

    @Test void issuingDoesNotRequireTenancyAndRecordsOnlyAdminConsent() {
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.selectOne(any())).thenReturn(defaultTemplate());
        SysUser admin = new SysUser(); admin.setRealName("王管理");
        when(sysUserMapper.selectById(ADMIN_ID)).thenReturn(admin);
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID);
        try (var auth = actor(false, ADMIN_ID)) {
            var result = agreementService.createAgreement(dto);
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
            assertThat(result.getAdminConfirmTime()).isNotNull();
            assertThat(result.getAdminConfirmName()).isEqualTo("王管理");
            assertThat(result.getTenantConfirmTime()).isNull();
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(house.getStatus()).isEqualTo("VACANT");
            verify(conversationService, never()).sendSystemMessage(any(), anyString());
        }
    }

    @Test void oldTenancyConsentNeverSignsNewSnapshot() {
        toSignLease.setTenantConfirmAt(LocalDateTime.now().minusDays(2));
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.selectOne(any())).thenReturn(defaultTemplate());
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID);
        try (var auth = actor(false, ADMIN_ID)) {
            assertThat(agreementService.createAgreement(dto).getTenantConfirmTime()).isNull();
        }
    }

    @Test void residentCannotIssueAgreement() {
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID);
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.createAgreement(dto)).isInstanceOf(ForbiddenException.class);
        }
    }

    @Test void activeLeaseCannotIssueNewActivationAgreement() {
        toSignLease.setStatus("ACTIVE");
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID);
        try (var auth = actor(false, ADMIN_ID)) {
            assertThatThrownBy(() -> agreementService.createAgreement(dto)).isInstanceOf(BusinessException.class);
            verify(agreementMapper, never()).insert(any(LeaseAgreement.class));
        }
    }

    @Test void adminCannotConfirmForResident() {
        toSignLease.setTenantConfirmAt(LocalDateTime.now().minusDays(2));
        try (var auth = actor(false, ADMIN_ID)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(pendingAgreement.getTenantConfirmTime()).isNull();
        }
    }

    @Test void residentConfirmationUsesCurrentAccountNotClientName() {
        AgreementConfirmDTO dto = new AgreementConfirmDTO(); dto.setConfirmName("代填他人姓名");
        try (var auth = actor(true, RESIDENT_ID)) {
            var result = agreementService.confirm(AGREEMENT_ID, dto);
            assertThat(result.getStatus()).isEqualTo("SIGNED");
            assertThat(result.getTenantConfirmName()).isEqualTo("张居民");
            assertThat(result.getTenantConfirmTime()).isAfter(pendingAgreement.getAdminConfirmTime());
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(house.getStatus()).isEqualTo("VACANT");
        }
    }

    @Test void repeatedSignedConfirmationIsReadOnly() {
        var first = LocalDateTime.now().minusHours(1);
        pendingAgreement.setStatus("SIGNED"); pendingAgreement.setTenantConfirmTime(first);
        pendingAgreement.setTenantConfirmName("首次本人"); toSignLease.setStatus("ACTIVE");
        try (var auth = actor(true, RESIDENT_ID)) {
            var result = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());
            assertThat(result.getTenantConfirmTime()).isEqualTo(first);
            assertThat(result.getTenantConfirmName()).isEqualTo("首次本人");
            verify(agreementMapper, never()).updateById(any(LeaseAgreement.class));
            verify(notificationService, never()).createForResident(any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        }
    }

    @Test void foreignResidentIsRejectedEvenForSignedAgreement() {
        pendingAgreement.setStatus("SIGNED");
        try (var auth = actor(true, 999L)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test void legacyAgreementWithoutAdminConsentRequiresReissue() {
        pendingAgreement.setAdminConfirmTime(null);
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("撤回");
            assertThat(pendingAgreement.getTenantConfirmTime()).isNull();
        }
    }

    @Test void cancelledSnapshotCannotBeAccepted() {
        pendingAgreement.setStatus("CANCELLED");
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test void disabledTemplateCannotBeIssued() {
        var template = defaultTemplate(); template.setStatus("INACTIVE");
        when(templateMapper.selectById(9L)).thenReturn(template);
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID); dto.setTemplateId(9L);
        try (var auth = actor(false, ADMIN_ID)) {
            assertThatThrownBy(() -> agreementService.createAgreement(dto)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void lockedCancelledSnapshotWinsOverStalePage() {
        LeaseAgreement cancelled = new LeaseAgreement();
        cancelled.setId(AGREEMENT_ID); cancelled.setLeaseId(LEASE_ID); cancelled.setTenantId(RESIDENT_ID);
        cancelled.setStatus("CANCELLED");
        when(agreementMapper.selectByIdForUpdate(AGREEMENT_ID)).thenReturn(cancelled);
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(BusinessException.class);
            verify(agreementMapper, never()).updateById(any(LeaseAgreement.class));
        }
    }

    @Test void signedWinnerCannotBeCancelledFromStaleAdminPage() {
        LeaseAgreement signed = new LeaseAgreement(); signed.setId(AGREEMENT_ID);
        signed.setLeaseId(LEASE_ID); signed.setCommunityId(3L); signed.setStatus("SIGNED");
        when(agreementMapper.selectByIdForUpdate(AGREEMENT_ID)).thenReturn(signed);
        try (var auth = actor(false, ADMIN_ID)) {
            assertThatThrownBy(() -> agreementService.cancel(AGREEMENT_ID, "修改条款"))
                    .isInstanceOf(BusinessException.class);
            verify(agreementMapper, never()).updateById(any(LeaseAgreement.class));
        }
    }

    @Test void reissueHasFreshSnapshotAndNoInheritedResidentConsent() {
        toSignLease.setTenantConfirmAt(LocalDateTime.now().minusDays(1));
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.selectOne(any())).thenReturn(defaultTemplate());
        try (var auth = actor(false, ADMIN_ID)) {
            var issued = agreementService.createAgreement(newCreate());
            assertThat(issued.getStatus()).isEqualTo("PARTIAL");
            assertThat(issued.getTenantConfirmTime()).isNull();
            assertThat(issued.getContent()).doesNotContain("{{");
        }
    }

    @Test void onlyOneOpenSnapshotMayExist() {
        when(agreementMapper.selectCount(any())).thenReturn(1L);
        try (var auth = actor(false, ADMIN_ID)) {
            assertThatThrownBy(() -> agreementService.createAgreement(newCreate()))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("撤回");
            verify(agreementMapper, never()).insert(any(LeaseAgreement.class));
        }
    }

    @Test void rejectedLeaseCannotBeAcceptedAfterPageWasOpened() {
        toSignLease.setStatus("REJECTED");
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(BusinessException.class);
            assertThat(pendingAgreement.getTenantConfirmTime()).isNull();
        }
    }

    @Test void residentCannotCancelAgreement() {
        try (var auth = actor(true, RESIDENT_ID)) {
            assertThatThrownBy(() -> agreementService.cancel(AGREEMENT_ID, "不同意"))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test void acceptLocksLeaseThenCurrentSnapshot() {
        try (var auth = actor(true, RESIDENT_ID)) {
            agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());
            var order = org.mockito.Mockito.inOrder(leaseMapper, agreementMapper);
            order.verify(leaseMapper).selectByIdForUpdate(LEASE_ID);
            order.verify(agreementMapper).selectByIdForUpdate(AGREEMENT_ID);
            order.verify(agreementMapper).updateById(any(LeaseAgreement.class));
        }
    }

    @Test void repeatedCancelDoesNotClearReissuedAgreementProgress() {
        pendingAgreement.setStatus("CANCELLED"); toSignLease.setAgreementStatus("PARTIAL");
        try (var auth = actor(false, ADMIN_ID)) {
            agreementService.cancel(AGREEMENT_ID, "相同请求重试");
            assertThat(toSignLease.getAgreementStatus()).isEqualTo("PARTIAL");
            verify(agreementMapper, never()).updateById(any(LeaseAgreement.class));
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
        }
    }

    private CreateAgreementDTO newCreate() {
        CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID); return dto;
    }

    private AgreementTemplate defaultTemplate() {
        AgreementTemplate template = new AgreementTemplate();
        template.setId(1L);
        template.setName("标准租赁协议");
        template.setContent("甲方：{{小区名称}}，房屋：{{房屋位置}}，月租：{{月租金}}");
        template.setIsDefault(1);
        template.setStatus("ACTIVE");
        template.setCommunityId(3L);
        return template;
    }
}
