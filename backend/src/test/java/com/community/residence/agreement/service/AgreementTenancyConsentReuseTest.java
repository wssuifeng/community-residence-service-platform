package com.community.residence.agreement.service;

import com.community.residence.agreement.dto.AgreementConfirmDTO;
import com.community.residence.agreement.entity.LeaseAgreement;
import com.community.residence.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.conversation.mapper.ConversationMapper;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.service.LeaseChangeLogService;
import com.community.residence.lease.service.LeaseService;
import com.community.residence.messaging.service.NotificationService;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 承约重复确认收口回归（DEF-078）：
 * 居民的「同意承租」只在承约端点采集一次——已承约的租约，**管理方确认协议时
 * 引用承约留痕回填居民侧确认**，双方齐即达成 SIGNED 并联动租约生效，
 * 居民无需为同一同意二次点击。
 * 反向断言：未承约时管理方单方确认仍只落 PARTIAL（不得借该规则误生效）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("协议确认复用承租确认留痕（DEF-078）")
class AgreementTenancyConsentReuseTest {

    private static final Long LEASE_ID = 8L;
    private static final Long AGREEMENT_ID = 22L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;

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
    private LocalDateTime tenancyConfirmedAt;

    @BeforeEach
    void setUp() {
        agreementService = new AgreementService(templateMapper, agreementMapper, leaseMapper,
                communityMapper, houseMapper, unitMapper, buildingMapper, residentMapper,
                sysUserMapper, notificationService, changeLogService, conversationMapper,
                conversationService, leaseService);

        tenancyConfirmedAt = LocalDateTime.now().minusHours(3);
        toSignLease = new LeaseRecord();
        toSignLease.setId(LEASE_ID);
        toSignLease.setTenantId(RESIDENT_ID);
        toSignLease.setCommunityId(3L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setStartDate(LocalDate.now());
        toSignLease.setEndDate(LocalDate.now().plusMonths(12));
        toSignLease.setConversationId(66L);
        /* 居民已通过承约端点表达同意承租 */
        toSignLease.setTenantConfirmAt(tenancyConfirmedAt);
        toSignLease.setTenantConfirmBy(RESIDENT_ID);

        pendingAgreement = new LeaseAgreement();
        pendingAgreement.setId(AGREEMENT_ID);
        pendingAgreement.setLeaseId(LEASE_ID);
        pendingAgreement.setCommunityId(3L);
        pendingAgreement.setTenantId(RESIDENT_ID);
        pendingAgreement.setTitle("演示社区1栋101 租赁协议");
        pendingAgreement.setStatus("PENDING");

        house = new House();
        house.setId(5L);
        house.setCommunityId(3L);
        house.setStatus("VACANT");

        Resident resident = new Resident();
        resident.setId(RESIDENT_ID);
        resident.setRealName("张居民");
        resident.setUsername("resident1");

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);
        when(agreementMapper.selectById(AGREEMENT_ID)).thenReturn(pendingAgreement);
        when(houseMapper.selectById(5L)).thenReturn(house);
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
        /* 统一内核条件更新（DEF-115）：正常流转返回 1 行 */
        when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
    }

    @Test
    @DisplayName("已承约：管理方确认即引用承约留痕补居民侧 → SIGNED + 租约 TO_SIGN→ACTIVE（居民不再二次确认）")
    void adminConfirm_afterTenancyConfirm_reusesTraceAndActivates() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);

            var vo = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());

            assertThat(vo.getStatus()).isEqualTo("SIGNED");
            /* 居民侧确认 = 承约留痕（时间取首次承约时间，早于管理方确认） */
            assertThat(pendingAgreement.getTenantConfirmTime()).isEqualTo(tenancyConfirmedAt);
            assertThat(pendingAgreement.getTenantConfirmName()).isEqualTo("张居民");
            assertThat(pendingAgreement.getAdminConfirmTime()).isAfter(tenancyConfirmedAt);
            /* 生效联动（R66 v1.8）不被绕过 */
            assertThat(toSignLease.getStatus()).isEqualTo("ACTIVE");
            assertThat(house.getStatus()).isEqualTo("OCCUPIED");
            verify(changeLogService).logStatus(eq(LEASE_ID), eq("TO_SIGN"), eq("ACTIVE"), anyString());
        }
    }

    @Test
    @DisplayName("未承约：管理方单方确认仍只落 PARTIAL、不生效（防误放行）")
    void adminConfirm_withoutTenancyConfirm_partialOnly() {
        toSignLease.setTenantConfirmAt(null);
        toSignLease.setTenantConfirmBy(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);

            var vo = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());

            assertThat(vo.getStatus()).isEqualTo("PARTIAL");
            assertThat(pendingAgreement.getTenantConfirmTime()).isNull();
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(house.getStatus()).isEqualTo("VACANT");
            verify(conversationService, never()).sendSystemMessage(anyLong(), anyString());
        }
    }

    @Test
    @DisplayName("居民先自行确认协议：不覆盖居民侧首次留痕（既有行为不回归）")
    void residentConfirmFirst_keepsOwnTrace() {
        toSignLease.setTenantConfirmAt(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);

            var vo = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());

            /* 居民确认后仅单方（管理方未确认）→ PARTIAL，居民侧时间为本人确认时刻 */
            assertThat(vo.getStatus()).isEqualTo("PARTIAL");
            assertThat(pendingAgreement.getTenantConfirmTime()).isNotNull();
            assertThat(pendingAgreement.getTenantConfirmName()).isEqualTo("张居民");
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
        }
    }
}
