package com.community.residence.lease.service;

import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.service.ViewingAppointmentService;
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
 * 承租确认（R67）与租约关联看房预约（R69）回归：
 * 仅本人 + 仅待签约、幂等且不覆盖首次留痕、通知本社区管理方；
 * 看房预约按 lease_id 归属且读权限沿用租约口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("租住签约闭环（R67/R69）")
class LeaseSigningClosureTest {

    private static final Long LEASE_ID = 7L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 9L;

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
    private com.community.residence.community.mapper.CommunityMapper communityMapper;
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
        toSignLease.setCommunityId(3L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setStartDate(LocalDate.now());
        toSignLease.setEndDate(LocalDate.now().plusMonths(12));
        toSignLease.setConversationId(66L);
        Resident resident = new Resident();
        resident.setRealName("张居民");
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
    }

    @Test
    @DisplayName("承租确认：本人待签约租约写入 tenant_confirm_at/by 并通知本社区管理员")
    void tenancyConfirm_ownToSignLease_writesTraceAndNotifiesAdmins() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);
            SysAdminCommunity binding = new SysAdminCommunity();
            binding.setAdminId(ADMIN_ID);
            binding.setCommunityId(3L);
            when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));

            LeaseVO vo = leaseService.tenancyConfirm(LEASE_ID);

            assertThat(vo.getTenantConfirmAt()).isNotNull();
            assertThat(vo.getTenantConfirmBy()).isEqualTo(RESIDENT_ID);
            assertThat(toSignLease.getStatus()).isEqualTo("TO_SIGN");
            verify(leaseMapper).updateById(toSignLease);
            verify(notificationService).createForUser(eq(ADMIN_ID), eq(3L), eq("居民已确认承租"),
                    anyString(), eq("LEASE"), eq("LEASE_TENANCY"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("承租确认幂等：重复确认不覆盖首次留痕、不重复通知")
    void tenancyConfirm_repeat_keepsFirstTrace() {
        toSignLease.setTenantConfirmAt(java.time.LocalDateTime.now().minusHours(2));
        toSignLease.setTenantConfirmBy(RESIDENT_ID);
        java.time.LocalDateTime first = toSignLease.getTenantConfirmAt();

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);

            LeaseVO vo = leaseService.tenancyConfirm(LEASE_ID);

            assertThat(vo.getTenantConfirmAt()).isEqualTo(first);
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(notificationService, never()).createForUser(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    @Test
    @DisplayName("承租确认越权：非本人 403")
    void tenancyConfirm_othersLease_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);

            assertThatThrownBy(() -> leaseService.tenancyConfirm(LEASE_ID))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("承租确认状态约束：非待签约拒绝 LEASE_STATUS_INVALID(5602)")
    void tenancyConfirm_nonToSign_rejected() {
        toSignLease.setStatus("ACTIVE");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);

            assertThatThrownBy(() -> leaseService.tenancyConfirm(LEASE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.LEASE_STATUS_INVALID));
        }
    }

    @Test
    @DisplayName("租约支付单列表：他人租约 403（沿用租约读权限）")
    void listByLease_othersLease_forbidden() {
        com.community.residence.payment.mapper.LeasePaymentMapper paymentMapper =
                org.mockito.Mockito.mock(com.community.residence.payment.mapper.LeasePaymentMapper.class);
        com.community.residence.payment.config.PaymentProperties props =
                org.mockito.Mockito.mock(com.community.residence.payment.config.PaymentProperties.class);
        com.community.residence.payment.service.PaymentService paymentService =
                new com.community.residence.payment.service.PaymentService(paymentMapper, leaseMapper,
                        sysAdminCommunityMapper, notificationService, props,
                        org.mockito.Mockito.mock(com.community.residence.payment.service.channel.PaymentChannelClient.class),
                        org.mockito.Mockito.mock(com.community.residence.payment.service.channel.PaymentChannelClient.class));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.constant.RoleConstants.RESIDENT))
                    .thenReturn(true);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);

            assertThatThrownBy(() -> paymentService.listByLease(LEASE_ID))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("租约关联看房预约：按 lease_id 归属返回，本人可见")
    void listByLease_returnsAppointments() {
        ViewingAppointmentMapper appointmentMapper =
                org.mockito.Mockito.mock(ViewingAppointmentMapper.class);
        ViewingAppointmentService viewingService = new ViewingAppointmentService(
                appointmentMapper,
                org.mockito.Mockito.mock(com.community.residence.housing.mapper.ViewingMessageMapper.class),
                org.mockito.Mockito.mock(com.community.residence.housing.mapper.HousingMapper.class),
                org.mockito.Mockito.mock(com.community.residence.housing.mapper.HousingTimeslotMapper.class),
                residentMapper,
                org.mockito.Mockito.mock(com.community.residence.auth.mapper.SysUserMapper.class),
                leaseMapper,
                sysAdminCommunityMapper,
                org.mockito.Mockito.mock(com.community.residence.resident.service.SysConfigService.class),
                org.mockito.Mockito.mock(com.community.residence.reservation.mapper.ViolationRecordMapper.class),
                org.mockito.Mockito.mock(com.community.residence.reservation.mapper.ResourceReservationMapper.class),
                org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class),
                notificationService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                org.mockito.Mockito.mock(com.community.residence.messaging.service.WebSocketSessionService.class),
                org.mockito.Mockito.mock(com.community.residence.conversation.service.ConversationService.class));

        ViewingAppointment appointment = new ViewingAppointment();
        appointment.setId(31L);
        appointment.setLeaseId(LEASE_ID);
        appointment.setHousingId(5L);
        appointment.setCommunityId(3L);
        appointment.setUserId(RESIDENT_ID);
        appointment.setAppointmentDate(LocalDate.now().plusDays(2));
        appointment.setStatus("TO_CONFIRM");
        when(appointmentMapper.selectList(any())).thenReturn(List.of(appointment));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.constant.RoleConstants.RESIDENT))
                    .thenReturn(true);
            when(leaseMapper.selectById(LEASE_ID)).thenReturn(toSignLease);

            var list = viewingService.listByLease(LEASE_ID);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getLeaseId()).isEqualTo(LEASE_ID);
        }
    }

    @Test
    @DisplayName("状态流转到已生效：房屋置已入住（TO_SIGN → ACTIVE 联动）")
    void applyTransition_toActive_marksHouseOccupied() {
        House house = new House();
        house.setId(5L);
        house.setCommunityId(3L);
        house.setStatus("VACANT");
        when(houseMapper.selectById(5L)).thenReturn(house);
        when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            leaseService.applyTransition(toSignLease, "ACTIVE", "协议双方确认完成");

            assertThat(toSignLease.getStatus()).isEqualTo("ACTIVE");
            assertThat(house.getStatus()).isEqualTo("OCCUPIED");
            verify(changeLogService).logStatus(LEASE_ID, "TO_SIGN", "ACTIVE", "协议双方确认完成");
        }
    }
}
