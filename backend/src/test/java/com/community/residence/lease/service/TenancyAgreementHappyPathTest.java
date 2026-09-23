package com.community.residence.lease.service;

import com.community.residence.agreement.dto.AgreementConfirmDTO;
import com.community.residence.agreement.dto.CreateAgreementDTO;
import com.community.residence.agreement.entity.AgreementTemplate;
import com.community.residence.agreement.entity.LeaseAgreement;
import com.community.residence.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.agreement.service.AgreementService;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.conversation.mapper.ConversationMapper;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 承约 → 协议 正向链路（2026-09-22 用户裁决后的目标口径）：
 * **管理端确认（审核通过 → 待签约）+ 居民端确认（承约）→ 管理方即可直接推进协议确认**，
 * 居民**零二次点击**（协议居民侧引用承约留痕，DEF-078 口径保留）。
 * 全程无看房预约、无租约群聊——证明两者确非承约门槛。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("管理端确认 + 居民确认 → 协议可直接推进（居民零二次点击）")
class TenancyAgreementHappyPathTest {

    private static final Long LEASE_ID = 12L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long AGREEMENT_ID = 77L;
    private static final Long HOUSE_ID = 5L;

    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private LeaseAgreementMapper agreementMapper;
    @Mock
    private AgreementTemplateMapper templateMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ConversationService conversationService;

    @InjectMocks
    private LeaseService leaseService;

    /* @InjectMocks 不会跨 @InjectMocks 注入真实 LeaseService，手工构造把两者接上
       （DEF-115：协议生效委托 LeaseService 统一流转内核，须用真实内核验证） */
    private AgreementService agreementService;

    private LeaseRecord lease;
    private House house;
    private LocalDateTime tenancyConfirmedAt;

    @BeforeEach
    void setUp() {
        agreementService = new AgreementService(templateMapper, agreementMapper, leaseMapper,
                communityMapper, houseMapper, unitMapper, buildingMapper, residentMapper,
                sysUserMapper, notificationService, changeLogService, conversationMapper,
                conversationService, leaseService);
        /* 管理端确认（审核通过 → 待签约）：状态 TO_SIGN；无看房、无群聊 */
        lease = new LeaseRecord();
        lease.setId(LEASE_ID);
        lease.setTenantId(RESIDENT_ID);
        lease.setCommunityId(1L);
        lease.setHouseId(HOUSE_ID);
        lease.setStatus("TO_SIGN");
        lease.setStartDate(LocalDate.now());
        lease.setEndDate(LocalDate.now().plusMonths(12));
        lease.setConversationId(null);

        house = new House();
        house.setId(HOUSE_ID);
        house.setCommunityId(1L);
        house.setStatus("VACANT");

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(lease);
        when(houseMapper.selectById(HOUSE_ID)).thenReturn(house);
        when(agreementMapper.selectById(AGREEMENT_ID)).thenReturn(null);
        when(agreementMapper.insert(any(LeaseAgreement.class))).thenAnswer(inv -> {
            LeaseAgreement created = inv.getArgument(0);
            created.setId(AGREEMENT_ID);
            /* 后续 confirm 走 selectById，须能取回同一条记录 */
            when(agreementMapper.selectById(AGREEMENT_ID)).thenReturn(created);
            return 1;
        });
        Resident resident = new Resident();
        resident.setId(RESIDENT_ID);
        resident.setRealName("张居民");
        resident.setUsername("resident1");
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(ADMIN_ID);
        binding.setCommunityId(1L);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        AgreementTemplate template = new AgreementTemplate();
        template.setId(1L);
        template.setName("标准租赁协议");
        template.setContent("甲方：{{社区名称}}，房屋：{{房屋位置}}，月租：{{月租金}}");
        template.setIsDefault(1);
        template.setStatus("ACTIVE");
        template.setCommunityId(1L);
        when(templateMapper.selectOne(any())).thenReturn(template);
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        /* 统一内核条件更新（DEF-115）：正常流转返回 1 行 */
        when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
    }

    @Test
    @DisplayName("居民承约（无看房/无群）→ 管理方发起协议 → 管理方确认 → SIGNED + 租约生效 + 房态翻转")
    void tenancyConfirmThenAgreement_singleResidentConsent() {
        /* ① 居民端确认（承约）：仅需本人 + 待签约 */
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            leaseService.tenancyConfirm(LEASE_ID);
            tenancyConfirmedAt = lease.getTenantConfirmAt();
            assertThat(tenancyConfirmedAt).isNotNull();
        }

        /* ② 管理方发起协议（前置=已承约，与看房/群聊无关） */
        CreateAgreementDTO createDto = new CreateAgreementDTO();
        createDto.setLeaseId(LEASE_ID);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
            var created = agreementService.createAgreement(createDto);
            assertThat(created.getStatus()).isEqualTo("PENDING");
        }

        /* ③ 管理方确认即达成 SIGNED → 租约生效；居民无需再确认一次 */
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
            var confirmed = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());

            assertThat(confirmed.getStatus()).isEqualTo("SIGNED");
            /* 居民侧确认 = 承约留痕引用（零二次点击） */
            assertThat(confirmed.getTenantConfirmTime()).isEqualTo(tenancyConfirmedAt);
            assertThat(confirmed.getTenantConfirmName()).isEqualTo("张居民");
            assertThat(confirmed.getAdminConfirmTime()).isNotNull();
            /* 租约生效 + 房态翻转（R66 v1.8 联动不被绕过） */
            assertThat(lease.getStatus()).isEqualTo("ACTIVE");
            assertThat(house.getStatus()).isEqualTo("OCCUPIED");
            assertThat(lease.getAgreementStatus()).isEqualTo("SIGNED");
            verify(changeLogService).logStatus(eq(LEASE_ID), eq("TO_SIGN"), eq("ACTIVE"), anyString());
        }
    }

    @Test
    @DisplayName("OCR DEF-115：协议生效走统一流转内核——越界状态（已搬出）双确认 → STATE_TRANSITION_INVALID(5004)")
    void confirm_bothSidesOnMovedOutLease_rejectedByUnifiedKernel() {
        lease.setStatus("MOVED_OUT");
        lease.setTenantConfirmAt(LocalDateTime.now().minusHours(1));
        lease.setTenantConfirmBy(RESIDENT_ID);

        /* 已搬出租约发起协议未被「仅 ARCHIVED 拦截」前置挡下（历史存量路径），
           管理方确认引用承约留痕补齐双方 → 触发生效联动 → 统一内核按状态机拒绝 */
        CreateAgreementDTO createDto = new CreateAgreementDTO();
        createDto.setLeaseId(LEASE_ID);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
            agreementService.createAgreement(createDto);

            assertThatThrownBy(() -> agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.STATE_TRANSITION_INVALID));
            assertThat(lease.getStatus()).isEqualTo("MOVED_OUT");
            assertThat(house.getStatus()).isEqualTo("VACANT");
        }
    }
}
