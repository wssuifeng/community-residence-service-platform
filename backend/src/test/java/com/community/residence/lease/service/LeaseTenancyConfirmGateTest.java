package com.community.residence.lease.service;

import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.vo.LeaseVO;
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
 * 承约门槛回归（2026-09-22 用户裁决后口径）：
 * **承约前置只有两条——本人 + 待签约状态**。
 * 「已完成看房预约」「租约沟通会话已建立」是**沟通手段，不是承约门槛**，
 * 故两者都缺失时承约必须正常成功（本类反转原 DEF-077 的 5 条前置用例，
 * 并显式断言不会再抛 5607/5608）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("承约门槛：本人 + 待签约（看房/群聊非门槛）")
class LeaseTenancyConfirmGateTest {

    private static final Long LEASE_ID = 9L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;

    @Mock
    private LeaseRecordMapper leaseMapper;
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
    private LeaseChangeLogService changeLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;

    @InjectMocks
    private LeaseService leaseService;

    private LeaseRecord toSignLease;

    @BeforeEach
    void setUp() {
        toSignLease = new LeaseRecord();
        toSignLease.setId(LEASE_ID);
        toSignLease.setTenantId(RESIDENT_ID);
        toSignLease.setCommunityId(1L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setStartDate(LocalDate.now());
        toSignLease.setEndDate(LocalDate.now().plusMonths(12));
        /* 裁决要点：无租约群聊（conversation_id 为空）也照样能承约 */
        toSignLease.setConversationId(null);

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);
        Resident resident = new Resident();
        resident.setRealName("张居民");
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(ADMIN_ID);
        binding.setCommunityId(1L);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
    }

    private MockedStatic<SecurityUtils> loginAsResident(Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        return mocked;
    }

    @Test
    @DisplayName("①无看房预约 + 无租约群聊 → 承约成功（不再抛 5607/5608）")
    void tenancyConfirm_withoutViewingAndConversation_succeeds() {
        try (MockedStatic<SecurityUtils> mocked = loginAsResident(RESIDENT_ID)) {
            LeaseVO vo = leaseService.tenancyConfirm(LEASE_ID);

            assertThat(vo.getTenantConfirmAt()).isNotNull();
            assertThat(vo.getTenantConfirmBy()).isEqualTo(RESIDENT_ID);
            /* 承约不改变租约状态（生效仍由协议双方确认触发，R66 v1.8） */
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            verify(leaseMapper).updateById(toSignLease);
            verify(changeLogService).logAgreement(eq(LEASE_ID), eq("居民确认承租"), anyString());
            verify(notificationService).createForUser(eq(ADMIN_ID), eq(1L), eq("居民已确认承租"),
                    anyString(), eq("LEASE"), eq("LEASE_TENANCY"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("②管理端已确认（TO_SIGN）+ 居民确认 → 承约留痕写入（正向门槛）")
    void tenancyConfirm_adminApprovedThenResident_succeeds() {
        try (MockedStatic<SecurityUtils> mocked = loginAsResident(RESIDENT_ID)) {
            LeaseVO vo = leaseService.tenancyConfirm(LEASE_ID);
            assertThat(vo.getTenantConfirmAt()).isNotNull();
            assertThat(vo.getStatus()).isEqualTo("TO_SIGN");
        }
    }

    @Test
    @DisplayName("③幂等：重复承约不覆盖首次留痕、不重复通知")
    void tenancyConfirm_repeat_keepsFirstTrace() {
        LocalDateTime first = LocalDateTime.now().minusDays(2);
        toSignLease.setTenantConfirmAt(first);
        toSignLease.setTenantConfirmBy(RESIDENT_ID);

        try (MockedStatic<SecurityUtils> mocked = loginAsResident(RESIDENT_ID)) {
            LeaseVO vo = leaseService.tenancyConfirm(LEASE_ID);

            assertThat(vo.getTenantConfirmAt()).isEqualTo(first);
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(notificationService, never()).createForUser(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    @Test
    @DisplayName("④越权：非本人承约 → 403（门槛之一仍生效）")
    void tenancyConfirm_othersLease_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = loginAsResident(999L)) {
            assertThatThrownBy(() -> leaseService.tenancyConfirm(LEASE_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权对他人租约确认承租");
        }
    }

    @Test
    @DisplayName("⑤状态门禁：非待签约 → 5602 LEASE_STATUS_INVALID（门槛之二仍生效）")
    void tenancyConfirm_nonToSign_rejected() {
        toSignLease.setStatus("ACTIVE");

        try (MockedStatic<SecurityUtils> mocked = loginAsResident(RESIDENT_ID)) {
            assertThatThrownBy(() -> leaseService.tenancyConfirm(LEASE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.LEASE_STATUS_INVALID));
        }
    }

    @Test
    @DisplayName("⑥已到期/已驳回等其它状态同样按状态机拒绝，且不写留痕")
    void tenancyConfirm_rejectedStatus_noTrace() {
        toSignLease.setStatus("REJECTED");

        try (MockedStatic<SecurityUtils> mocked = loginAsResident(RESIDENT_ID)) {
            assertThatThrownBy(() -> leaseService.tenancyConfirm(LEASE_ID))
                    .isInstanceOf(BusinessException.class);
            assertThat(toSignLease.getTenantConfirmAt()).isNull();
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
        }
    }
}
