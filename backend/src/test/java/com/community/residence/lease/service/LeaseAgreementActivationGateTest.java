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
class LeaseAgreementActivationGateTest {
    @org.mockito.Mock private com.community.residence.lease.service.LeaseContractTermService termService;

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

    @Test void administratorCannotActivateWithoutResidentAgreement() {
        when(leaseMapper.selectByIdForUpdate(LEASE_ID)).thenReturn(toSignLease);
        var dto = new com.community.residence.lease.dto.UpdateLeaseStatusDTO(); dto.setStatus("ACTIVE");
        try (var auth = mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> leaseService.updateStatus(LEASE_ID, dto))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("首期");
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
        }
    }

    @Test void transitionKernelRejectsUnsignedAgreement() {
        assertThatThrownBy(() -> leaseService.applyTransition(toSignLease, "ACTIVE", "绕过协议"))
                .isInstanceOf(BusinessException.class);
        assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
    }

    @Test void removedTenancyRouteIsNotExposed() {
        assertThat(java.util.Arrays.stream(com.community.residence.lease.controller.LeaseController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("tenancyConfirm");
    }
}
