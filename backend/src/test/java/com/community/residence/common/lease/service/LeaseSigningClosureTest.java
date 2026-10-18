package com.community.residence.common.lease.service;

import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.housing.service.ViewingAppointmentService;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.vo.LeaseVO;
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
    @org.mockito.Mock private com.community.residence.common.lease.service.LeaseContractTermService termService;

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
    private com.community.residence.common.community.mapper.CommunityMapper communityMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;

    @Mock private com.community.residence.common.payment.mapper.LeasePaymentMapper paymentMapper;
    @Mock private com.community.residence.common.resident.mapper.ResidenceRelationMapper relationMapper;
    @Mock private com.community.residence.common.agreement.mapper.LeaseAgreementMapper agreementMapper;
    @Mock private com.community.residence.common.conversation.service.ConversationService conversationService;
    @InjectMocks
    private LeaseService leaseService;

    private LeaseRecord toSignLease;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), com.community.residence.common.agreement.entity.LeaseAgreement.class);
        org.mockito.Mockito.lenient().when(paymentMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        toSignLease = new LeaseRecord();
        toSignLease.setId(LEASE_ID);
        toSignLease.setTenantId(RESIDENT_ID);
        toSignLease.setCommunityId(3L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setAgreementStatus("SIGNED");
        toSignLease.setStartDate(LocalDate.now());
        toSignLease.setEndDate(LocalDate.now().plusMonths(12));
        toSignLease.setConversationId(66L);
        Resident resident = new Resident();
        resident.setRealName("张居民");
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
    }

    @Test
    @DisplayName("租约支付单列表：他人租约 403（沿用租约读权限）")
    void listByLease_othersLease_forbidden() {
        com.community.residence.common.payment.mapper.LeasePaymentMapper paymentMapper =
                org.mockito.Mockito.mock(com.community.residence.common.payment.mapper.LeasePaymentMapper.class);
        com.community.residence.common.payment.config.PaymentProperties props =
                org.mockito.Mockito.mock(com.community.residence.common.payment.config.PaymentProperties.class);
        com.community.residence.common.payment.service.PaymentService paymentService =
                new com.community.residence.common.payment.service.PaymentService(paymentMapper, leaseMapper,
                        sysAdminCommunityMapper, notificationService, props,
                        org.mockito.Mockito.mock(com.community.residence.common.payment.service.channel.PaymentChannelClient.class),
                        org.mockito.Mockito.mock(com.community.residence.common.payment.service.channel.PaymentChannelClient.class));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT))
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
                org.mockito.Mockito.mock(com.community.residence.common.conversation.service.ChatImageService.class),
                appointmentMapper,
                org.mockito.Mockito.mock(com.community.residence.common.housing.mapper.ViewingMessageMapper.class),
                org.mockito.Mockito.mock(com.community.residence.common.housing.mapper.HousingMapper.class),
                org.mockito.Mockito.mock(com.community.residence.common.housing.mapper.HousingTimeslotMapper.class),
                residentMapper,
                org.mockito.Mockito.mock(com.community.residence.common.auth.mapper.SysUserMapper.class),
                leaseMapper,
                sysAdminCommunityMapper,
                org.mockito.Mockito.mock(com.community.residence.common.auth.service.SysConfigService.class),
                org.mockito.Mockito.mock(com.community.residence.common.reservation.mapper.ViolationRecordMapper.class),
                org.mockito.Mockito.mock(com.community.residence.common.reservation.mapper.ResourceReservationMapper.class),
                org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class),
                notificationService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                org.mockito.Mockito.mock(com.community.residence.common.messaging.service.WebSocketSessionService.class),
                org.mockito.Mockito.mock(com.community.residence.common.conversation.service.ConversationService.class),
                org.mockito.Mockito.mock(com.community.residence.common.auth.service.TokenRevocationService.class));

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
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.security.constant.RoleConstants.RESIDENT))
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
        house.setStatus("VACANT");
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
