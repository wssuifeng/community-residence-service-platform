package com.community.residence.housing.service;

import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
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
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private NotificationService notificationService;

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
        lenient().when(appointmentMapper.updateById(any(ViewingAppointment.class))).thenReturn(1);
    }

    @Test
    @DisplayName("确认：预约人收到通知（原零通知）")
    void confirm_notifiesApplicant() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.confirm(1L, "ok");
            verify(notificationService).create(eq(5L), eq(1L), eq("看房预约已确认"),
                    anyString(), anyString(), anyString(), eq(1L));
        }
    }

    @Test
    @DisplayName("完成：预约人收到通知")
    void complete_notifiesApplicant() {
        appointment.setStatus("RESERVED");
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.complete(1L, "done");
            verify(notificationService).create(eq(5L), eq(1L), eq("看房预约已完成"),
                    anyString(), anyString(), anyString(), eq(1L));
        }
    }

    @Test
    @DisplayName("取消：预约人收到通知（含原因）")
    void cancel_notifiesApplicant() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockAdmin()) {
            service.cancel(1L, "时间冲突");
            verify(notificationService).create(eq(5L), eq(1L), eq("看房预约已取消"),
                    anyString(), anyString(), anyString(), eq(1L));
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
            verify(notificationService).create(eq(5L), eq(1L), eq("看房预约违约处置"),
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
            verify(notificationService, never()).create(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockAdmin() {
        MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                mockStatic(com.community.residence.common.context.SecurityUtils.class);
        mocked.when(com.community.residence.common.context.SecurityUtils::getUser)
                .thenReturn(new com.community.residence.common.context.UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
        return mocked;
    }
}
