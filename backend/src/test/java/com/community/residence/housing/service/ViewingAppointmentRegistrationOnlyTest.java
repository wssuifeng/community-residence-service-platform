package com.community.residence.housing.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
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
import static org.mockito.Mockito.when;

/** DEF-158：方案 B 看房预约身份与数据级权限回归。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看房预约注册居民权限回归（DEF-158）")
class ViewingAppointmentRegistrationOnlyTest {

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private com.community.residence.housing.mapper.ViewingMessageMapper messageMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private com.community.residence.auth.mapper.SysUserMapper sysUserMapper;
    @Mock
    private com.community.residence.lease.mapper.LeaseRecordMapper leaseMapper;
    @Mock
    private com.community.residence.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
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
    private com.community.residence.messaging.service.NotificationService notificationService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private com.community.residence.messaging.service.WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

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
                    .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
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
                    .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
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
                    .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
                    .hasMessageContaining("仅可查看分配给本人的预约");
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

    private MockedStatic<SecurityUtils> residentIdentity(Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.context.UserContext(
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
                .thenReturn(new com.community.residence.common.context.UserContext(
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
                .thenReturn(new com.community.residence.common.context.UserContext(
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
}
