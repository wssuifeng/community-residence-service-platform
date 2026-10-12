package com.community.residence.agreement.service;

import com.community.residence.agreement.dto.AgreementConfirmDTO;
import com.community.residence.agreement.dto.CreateAgreementDTO;
import com.community.residence.agreement.entity.AgreementTemplate;
import com.community.residence.agreement.entity.LeaseAgreement;
import com.community.residence.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.conversation.entity.Conversation;
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
class AgreementConsentBoundaryTest {

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
        pendingAgreement.setTitle("演示社区1栋101 租赁协议");
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

    @Test void issueThenAcceptUsesActualResidentAction() {
        when(agreementMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.selectOne(any())).thenReturn(defaultTemplate());
        when(agreementMapper.insert(any(LeaseAgreement.class))).thenAnswer(inv -> {
            pendingAgreement = inv.getArgument(0); pendingAgreement.setId(AGREEMENT_ID);
            when(agreementMapper.selectById(AGREEMENT_ID)).thenReturn(pendingAgreement);
            when(agreementMapper.selectByIdForUpdate(AGREEMENT_ID)).thenReturn(pendingAgreement);
            return 1;
        });
        try (var auth = actor(false, ADMIN_ID)) {
            CreateAgreementDTO dto = new CreateAgreementDTO(); dto.setLeaseId(LEASE_ID);
            var issued = agreementService.createAgreement(dto);
            assertThat(issued.getAdminConfirmTime()).isNotNull();
            assertThat(issued.getTenantConfirmTime()).isNull();
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(house.getStatus()).isEqualTo("VACANT");
        }
        try (var auth = actor(true, RESIDENT_ID)) {
            var accepted = agreementService.confirm(AGREEMENT_ID, new AgreementConfirmDTO());
            assertThat(accepted.getStatus()).isEqualTo("SIGNED");
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            assertThat(house.getStatus()).isEqualTo("VACANT");
            verify(changeLogService, never()).logStatus(eq(LEASE_ID), eq("TO_SIGN"), eq("ACTIVE"), anyString());
            verify(conversationService).sendSystemMessage(eq(CONVERSATION_ID), anyString());
        }
    }

    private AgreementTemplate defaultTemplate() {
        AgreementTemplate template = new AgreementTemplate();
        template.setId(1L);
        template.setName("标准租赁协议");
        template.setContent("甲方：{{社区名称}}，房屋：{{房屋位置}}，月租：{{月租金}}");
        template.setIsDefault(1);
        template.setStatus("ACTIVE");
        template.setCommunityId(3L);
        return template;
    }
}
