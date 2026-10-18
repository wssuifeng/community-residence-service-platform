package com.community.residence.common.housing.service;

import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.entity.HousingTimeslot;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DEF-158：方案 B 看房预约身份与数据级权限回归。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看房预约注册居民权限回归（DEF-158）")
class ViewingAppointmentRegistrationOnlyTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private com.community.residence.common.housing.mapper.ViewingMessageMapper messageMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private com.community.residence.common.auth.mapper.SysUserMapper sysUserMapper;
    @Mock
    private com.community.residence.common.lease.mapper.LeaseRecordMapper leaseMapper;
    @Mock
    private com.community.residence.common.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private ResourceReservationMapper resourceReservationMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private com.community.residence.common.messaging.service.NotificationService notificationService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private com.community.residence.common.messaging.service.WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.common.conversation.service.ConversationService conversationService;

    @InjectMocks
    private ViewingAppointmentService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        lenient().when(sysConfigService.getValue("viewing.max_continuous_minutes")).thenReturn(null);
        lenient().when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        lenient().when(appointmentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);
        lenient().when(appointmentMapper.updateById(any(ViewingAppointment.class))).thenReturn(1);
    }

    @Test
    @DisplayName("未认证游客不能创建预约，服务层拒绝在房源查询前生效")
    void guestCreate_isRejectedBeforeWrite() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(null);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);

            assertThatThrownBy(() -> service.create(dto()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("只有注册并登录的居民账号可预约看房");
        }
        verify(housingMapper, never()).selectById(anyLong());
        verify(appointmentMapper, never()).insert(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("居民创建预约绑定 JWT 身份，不消费游客姓名字段")
    void residentCreate_bindsResidentIdentity() {
        Housing housing = housing();
        Resident resident = new Resident();
        resident.setId(7L);
        resident.setRealName("居民甲");
        resident.setPhone("13800001111");
        when(housingMapper.selectById(1L)).thenReturn(housing);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(timeslot()));
        when(residentMapper.selectById(7L)).thenReturn(resident);

        try (MockedStatic<SecurityUtils> mocked = residentIdentity(7L)) {
            var result = service.create(dto());

            assertThat(result).isNotNull();
            var captor = org.mockito.ArgumentCaptor.forClass(ViewingAppointment.class);
            verify(appointmentMapper).insert(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(7L);
            assertThat(captor.getValue().getVisitorName()).isNull();
            assertThat(captor.getValue().getContactPhone()).isEqualTo("13800001111");
        }
    }

    @Test
    @DisplayName("居民不能查询他人预约")
    void residentDetail_otherResidentIsRejected() {
        ViewingAppointment appointment = appointment();
        appointment.setUserId(8L);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = residentIdentity(7L)) {
            assertThatThrownBy(() -> service.getById(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权查看他人预约");
        }
    }

    @Test
    @DisplayName("居民不能查询历史游客预约（userId 为空）")
    void residentDetail_legacyGuestRecordIsNotClaimable() {
        ViewingAppointment appointment = appointment();
        appointment.setUserId(null);
        appointment.setVisitorName("历史访客");
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = residentIdentity(7L)) {
            assertThatThrownBy(() -> service.getById(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权查看他人预约");
        }
    }

    @Test
    @DisplayName("管理端可在绑定社区范围内读取历史游客预约")
    void adminDetail_legacyGuestRecordWithinCommunityIsReadable() {
        ViewingAppointment appointment = appointment();
        appointment.setUserId(null);
        appointment.setVisitorName("历史访客");
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = adminIdentity(9L, Set.of(appointment.getCommunityId()))) {
            var result = service.getById(1L);
            assertThat(result.getUserId()).isNull();
            assertThat(result.getVisitorName()).isEqualTo("历史访客");
            mocked.verify(() -> SecurityUtils.checkCommunityAccess(appointment.getCommunityId()));
        }
    }

    @Test
    @DisplayName("管理端读取历史游客预约时仍校验社区范围")
    void adminDetail_legacyGuestRecordOutsideCommunityIsRejected() {
        ViewingAppointment appointment = appointment();
        appointment.setUserId(null);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = adminIdentity(9L, Set.of())) {
            mocked.when(() -> SecurityUtils.checkCommunityAccess(appointment.getCommunityId()))
                    .thenThrow(new ForbiddenException("无权操作未绑定社区的数据"));
            assertThatThrownBy(() -> service.getById(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("未绑定社区");
        }
    }

    @Test
    @DisplayName("服务人员仅能查看分配给本人的预约")
    void staffDetail_unassignedIsRejected() {
        ViewingAppointment appointment = appointment();
        appointment.setAssignedStaffId(12L);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = staffIdentity(13L)) {
            assertThatThrownBy(() -> service.getById(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅可查看分配给本人的预约");
        }
    }

    @Test
    @DisplayName("本人被分配人员可以登记完成并留下备注")
    void assignedStaffCanComplete() {
        ViewingAppointment item = appointment(); item.setStatus("RESERVED"); item.setAssignedStaffId(12L);
        lenient().when(appointmentMapper.selectById(1L)).thenReturn(item);
        lenient().when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(item);
        try (MockedStatic<SecurityUtils> mocked = staffIdentity(12L)) {
            service.complete(1L, "本人完成带看");
            assertThat(item.getStatus()).isEqualTo("COMPLETED");
            assertThat(item.getRemark()).isEqualTo("本人完成带看");
            verify(appointmentMapper).updateById(item);
        }
    }

    @Test
    @DisplayName("其他人员即使知道预约ID也不能登记完成")
    void otherStaffCannotComplete() {
        ViewingAppointment item = appointment(); item.setStatus("RESERVED"); item.setAssignedStaffId(12L);
        lenient().when(appointmentMapper.selectById(1L)).thenReturn(item);
        lenient().when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(item);
        try (MockedStatic<SecurityUtils> mocked = staffIdentity(13L)) {
            assertThatThrownBy(() -> service.complete(1L, "越权完成")).isInstanceOf(ForbiddenException.class);
            verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
        }
    }

    @Test
    @DisplayName("居民与游客不能借同ID登记完成")
    void residentAndGuestCannotComplete() {
        ViewingAppointment item = appointment(); item.setStatus("RESERVED"); item.setAssignedStaffId(12L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(item);
        for (String role : List.of(RoleConstants.RESIDENT, RoleConstants.GUEST)) {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(() -> SecurityUtils.hasRole(role)).thenReturn(true);
                mocked.when(SecurityUtils::getUserId).thenReturn(12L);
                assertThatThrownBy(() -> service.complete(1L, "同号越权")).isInstanceOf(ForbiddenException.class);
            }
        }
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("服务人员不能处置违约")
    void staffCannotViolate() {
        try (MockedStatic<SecurityUtils> mocked = staffIdentity(12L)) {
            assertThatThrownBy(() -> service.violate(1L, "无处置权")).isInstanceOf(ForbiddenException.class);
            verifyNoInteractions(violationRecordMapper);
        }
    }

    @Test
    @DisplayName("完成时管理端继续校验社区范围")
    void adminCompletionChecksCommunity() {
        ViewingAppointment item = appointment(); item.setStatus("RESERVED");
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(item);
        try (MockedStatic<SecurityUtils> mocked = adminIdentity(9L, Set.of())) {
            mocked.when(() -> SecurityUtils.checkCommunityAccess(item.getCommunityId()))
                    .thenThrow(new ForbiddenException("未绑定社区"));
            assertThatThrownBy(() -> service.complete(1L, "越社区")).isInstanceOf(ForbiddenException.class);
            verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
        }
    }

    @Test
    @DisplayName("服务人员不能取消居民预约")
    void staffCancel_isRejected() {
        when(appointmentMapper.selectById(1L)).thenReturn(appointment());

        try (MockedStatic<SecurityUtils> mocked = staffIdentity(12L)) {
            assertThatThrownBy(() -> service.cancel(1L, "不应允许"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("服务人员与游客无权取消");
        }
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("居民可取消本人待确认预约")
    void residentCancel_ownAppointmentSucceeds() {
        ViewingAppointment appointment = appointment();
        appointment.setUserId(7L);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        try (MockedStatic<SecurityUtils> mocked = residentIdentity(7L)) {
            service.cancel(1L, "临时有事");
        }

        assertThat(appointment.getStatus()).isEqualTo("CANCELLED");
        verify(appointmentMapper).updateById(appointment);
    }

    @Test
    @DisplayName("其他居民取消预约使用专用403拒绝，且不写入状态")
    void residentCancel_otherResidentUsesForbiddenException() {
        ViewingAppointment row = appointment(); row.setUserId(7L);
        when(appointmentMapper.selectById(1L)).thenReturn(row);
        try (MockedStatic<SecurityUtils> mocked = residentIdentity(8L)) {
            assertThatThrownBy(() -> service.cancel(1L, "不应执行"))
                    .isInstanceOf(ForbiddenException.class);
        }
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    private MockedStatic<SecurityUtils> residentIdentity(Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.security.context.UserContext(
                        userId, "resident" + userId, RoleConstants.RESIDENT, Set.of()));
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(false);
        return mocked;
    }

    private MockedStatic<SecurityUtils> adminIdentity(Long userId, Set<Long> communityIds) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.security.context.UserContext(
                        userId, "admin" + userId, RoleConstants.ADMIN, communityIds));
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(true);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(false);
        return mocked;
    }

    private MockedStatic<SecurityUtils> staffIdentity(Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.security.context.UserContext(
                        userId, "staff" + userId, RoleConstants.STAFF, Set.of()));
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF)).thenReturn(true);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(false);
        return mocked;
    }

    private CreateViewingAppointmentDTO dto() {
        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setHousingId(1L);
        dto.setAppointmentDate(LocalDate.now().plusDays(1));
        dto.setStartTime(LocalTime.of(10, 0));
        dto.setEndTime(LocalTime.of(11, 0));
        dto.setVisitorName("不应作为身份来源");
        return dto;
    }

    private Housing housing() {
        Housing housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
        housing.setTitle("测试房源");
        return housing;
    }

    private HousingTimeslot timeslot() {
        HousingTimeslot timeslot = new HousingTimeslot();
        timeslot.setHousingId(1L);
        timeslot.setDayOfWeek(LocalDate.now().plusDays(1).getDayOfWeek().getValue());
        timeslot.setStartTime(LocalTime.of(9, 0));
        timeslot.setEndTime(LocalTime.of(12, 0));
        timeslot.setIsAvailable(1);
        return timeslot;
    }

    private ViewingAppointment appointment() {
        ViewingAppointment appointment = new ViewingAppointment();
        appointment.setId(1L);
        appointment.setHousingId(1L);
        appointment.setCommunityId(1L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(10, 0));
        appointment.setEndTime(LocalTime.of(11, 0));
        appointment.setStatus("TO_CONFIRM");
        return appointment;
    }
    @org.junit.jupiter.api.BeforeEach
    void rentalAvailabilityFixture() {
        org.mockito.Mockito.lenient().when(housingMapper.publicAvailabilityCount(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1L);
    }
}
