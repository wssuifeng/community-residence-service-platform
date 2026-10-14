package com.community.residence.housing.service;

import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.resident.mapper.ResidentMapper;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-023 修复回归（R55「确认/取消均通知对方」+ R48）：
 * 看房预约 confirm/complete/violate/cancel 四类流转通知预约人。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看房预约流转通知修复回归（DEF-023）")
class ViewingNotificationFixTest {

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private ResourceReservationMapper resourceReservationMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

    @InjectMocks
    private ViewingAppointmentService service;

    private ViewingAppointment appointment;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        appointment = new ViewingAppointment();
        appointment.setId(1L);
        appointment.setUserId(5L);
        appointment.setHousingId(1L);
        appointment.setCommunityId(1L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(3));
        appointment.setStatus("TO_CONFIRM");
        lenient().when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        lenient().when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(appointment);
        lenient().when(appointmentMapper.updateById(any(ViewingAppointment.class))).thenReturn(1);
    }

    @Test
    @DisplayName("创建：社区绑定管理员收到新看房预约通知")
    void create_notifiesBoundAdmins() {
        Housing housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
        housing.setHouseId(1L);
        when(housingMapper.selectById(1L)).thenReturn(housing);
        when(sysConfigService.getValue(anyString())).thenReturn(null);
        when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        HousingTimeslot timeslot = new HousingTimeslot();
        timeslot.setHousingId(1L);
        timeslot.setDayOfWeek(dtoDayOfWeek(LocalDate.now().plusDays(3)));
        timeslot.setStartTime(java.time.LocalTime.of(9, 0));
        timeslot.setEndTime(java.time.LocalTime.of(12, 0));
        timeslot.setIsAvailable(1);
        when(timeslotMapper.selectList(any())).thenReturn(java.util.List.of(timeslot));
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(java.util.List.of(
                adminCommunity(2L), adminCommunity(3L)));
        when(residentMapper.selectById(5L)).thenReturn(null);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenAnswer(invocation -> {
            ViewingAppointment value = invocation.getArgument(0);
            value.setId(88L);
            return 1;
        });
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockResident()) {
            var dto = new com.community.residence.housing.dto.CreateViewingAppointmentDTO();
            dto.setHousingId(1L);
            dto.setAppointmentDate(LocalDate.now().plusDays(3));
            dto.setStartTime(java.time.LocalTime.of(10, 0));
            dto.setEndTime(java.time.LocalTime.of(10, 30));
            service.create(dto);
            verify(notificationService).createForUser(eq(2L), eq(1L), eq("新看房预约"),
                    anyString(), eq("RESERVATION"), eq("VIEWING_APPOINTMENT"), eq(88L));
            verify(notificationService).createForUser(eq(3L), eq(1L), eq("新看房预约"),
                    anyString(), eq("RESERVATION"), eq("VIEWING_APPOINTMENT"), eq(88L));
        }
    }

    @Test
    @DisplayName("确认：预约人收到通知（原零通知）")
    void confirm_notifiesApplicant() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.confirm(1L, "ok");
            verify(notificationService).createForResident(eq(5L), eq(1L), eq("看房预约已确认"),
                    anyString(), anyString(), anyString(), eq(1L));
        }
    }

    @Test
    @DisplayName("完成：预约人收到通知")
    void complete_notifiesApplicant() {
        appointment.setStatus("RESERVED");
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.complete(1L, "done");
            verify(notificationService).createForResident(eq(5L), eq(1L), eq("看房预约已完成"),
                    anyString(), anyString(), anyString(), eq(1L));
        }
    }

    @Test
    @DisplayName("取消：预约人收到通知（含原因）")
    void cancel_notifiesApplicant() {
        appointment.setConversationId(55L);
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.cancel(1L, "时间冲突");
            verify(notificationService).createForResident(eq(5L), eq(1L), eq("看房预约已取消"),
                    anyString(), anyString(), anyString(), eq(1L));
            verify(conversationService).closeViewingGroup(1L);
        }
    }

    @Test
    void cancellingLeaseViewingDoesNotCloseParentLeaseConversation() {
        appointment.setLeaseId(88L);appointment.setConversationId(55L);
        try(var auth=mockResident()) {
            service.cancel(1L,"个人日程调整");
            verify(conversationService,never()).closeConversation(anyLong(),anyString());
            verify(conversationService).sendSystemMessage(eq(55L),anyString());
        }
    }

    @Test
    @DisplayName("居民取消：绑定管理员收到取消通知，居民不重复收件")
    void residentCancel_notifiesBoundAdmins() {
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(java.util.List.of(
                adminCommunity(2L), adminCommunity(3L)));
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockResident()) {
            service.cancel(1L, "临时有事");
            verify(notificationService).createForUser(eq(2L), eq(1L), eq("看房预约已取消"),
                    anyString(), eq("RESERVATION"), eq("VIEWING_APPOINTMENT"), eq(1L));
            verify(notificationService).createForUser(eq(3L), eq(1L), eq("看房预约已取消"),
                    anyString(), eq("RESERVATION"), eq("VIEWING_APPOINTMENT"), eq(1L));
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    @Test
    @DisplayName("违约处置：预约人收到通知 + 写违约记录")
    void violate_notifiesAndRecords() {
        appointment.setStatus("RESERVED");
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            lenient().when(violationRecordMapper.insert(
                    any(com.community.residence.reservation.entity.ViolationRecord.class))).thenReturn(1);
            service.violate(1L, "未到场");
            verify(notificationService).createForResident(eq(5L), eq(1L), eq("看房预约违约处置"),
                    anyString(), anyString(), anyString(), eq(1L));
            verify(violationRecordMapper).insert(
                    any(com.community.residence.reservation.entity.ViolationRecord.class));
        }
    }

    @Test
    @DisplayName("游客预约（无 userId）：通知跳过不报错（R55 口径内边界）")
    void guestAppointment_noNotification_noError() {
        appointment.setUserId(null);
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.confirm(1L, "ok");
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    private int dtoDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    private SysAdminCommunity adminCommunity(Long adminId) {
        var binding = new com.community.residence.auth.entity.SysAdminCommunity();
        binding.setAdminId(adminId);
        binding.setCommunityId(1L);
        return binding;
    }

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockResident() {
        MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                mockStatic(com.community.residence.common.context.SecurityUtils.class);
        mocked.when(com.community.residence.common.context.SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.context.UserContext(5L, "resident1", "RESIDENT", Set.of(1L)));
        mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
        mocked.when(() -> com.community.residence.common.context.SecurityUtils.hasRole("RESIDENT"))
                .thenReturn(true);
        mocked.when(() -> com.community.residence.common.context.SecurityUtils.hasRole("ADMIN"))
                .thenReturn(false);
        mocked.when(() -> com.community.residence.common.context.SecurityUtils.hasRole("SUPER_ADMIN"))
                .thenReturn(false);
        return mocked;
    }

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockAdmin() {
        MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                mockStatic(com.community.residence.common.context.SecurityUtils.class);
        mocked.when(com.community.residence.common.context.SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.context.UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
        mocked.when(() -> com.community.residence.common.context.SecurityUtils.hasRole("ADMIN"))
                .thenReturn(true);
        mocked.when(() -> com.community.residence.common.context.SecurityUtils.hasRole("SUPER_ADMIN"))
                .thenReturn(false);
        return mocked;
    }
    @org.junit.jupiter.api.BeforeEach
    void rentalAvailabilityFixture() {
        org.mockito.Mockito.lenient().when(housingMapper.publicAvailabilityCount(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1L);
    }
}


