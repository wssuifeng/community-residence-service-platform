package com.community.residence.common.reservation.service;

import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.community.entity.PublicResource;
import com.community.residence.common.community.entity.ResourceTimeslot;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.reservation.dto.CreateReservationDTO;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DEF-176：联系电话选填时使用本人档案，不向非空列写NULL。 */
@ExtendWith(MockitoExtension.class)
class ReservationContactFallbackTest {
    @Mock ResourceReservationMapper reservationMapper;
    @Mock ViolationRecordMapper violationRecordMapper;
    @Mock PublicResourceMapper resourceMapper;
    @Mock ResourceTimeslotMapper timeslotMapper;
    @Mock ViewingAppointmentMapper viewingAppointmentMapper;
    @Mock ResidentMapper residentMapper;
    @Mock SysConfigService sysConfigService;
    @Mock NotificationService notificationService;
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;
    @InjectMocks ReservationService service;
    private CreateReservationDTO dto;
    private Resident resident;

    @BeforeEach
    void fixture() throws InterruptedException {
        PublicResource resource = new PublicResource();
        resource.setId(1L); resource.setCommunityId(1L); resource.setCapacity(1); resource.setSlotUnit(30);
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        ResourceTimeslot slot = new ResourceTimeslot();
        slot.setResourceId(1L); slot.setCommunityId(1L); slot.setDayOfWeek(LocalDate.now().plusDays(1).getDayOfWeek().getValue());
        slot.setStartTime(LocalTime.of(9, 0)); slot.setEndTime(LocalTime.of(12, 0)); slot.setIsAvailable(1);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(reservationMapper.selectCount(any())).thenReturn(0L);
        lenient().when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);
        dto = new CreateReservationDTO(); dto.setResourceId(1L); dto.setReserveDate(LocalDate.now().plusDays(1));
        dto.setStartTime(LocalTime.of(9, 0)); dto.setEndTime(LocalTime.of(9, 30));
        resident = new Resident(); resident.setId(20L); resident.setPhone("13900000001");
        lenient().when(residentMapper.selectById(20L)).thenReturn(resident);
    }

    private void create() {
        try (MockedStatic<SecurityUtils> auth = mockStatic(SecurityUtils.class)) {
            auth.when(SecurityUtils::getUserId).thenReturn(20L);
            service.create(dto);
        }
    }

    @Test
    void omittedContactUsesResidentPhone() {
        create();
        verify(reservationMapper).insert(argThat((ResourceReservation row) -> "13900000001".equals(row.getContactPhone())));
    }

    @Test
    void blankContactUsesResidentPhone() {
        dto.setContactPhone(" "); create();
        verify(reservationMapper).insert(argThat((ResourceReservation row) -> "13900000001".equals(row.getContactPhone())));
    }

    @Test
    void explicitContactRemainsUnchanged() {
        dto.setContactPhone("13800000002"); create();
        verify(reservationMapper).insert(argThat((ResourceReservation row) -> "13800000002".equals(row.getContactPhone())));
    }

    @Test
    void missingResidentFailsBeforeInsert() {
        when(residentMapper.selectById(20L)).thenReturn(null);
        assertThatThrownBy(this::create).isInstanceOf(BusinessException.class);
        verify(reservationMapper, never()).insert(any(ResourceReservation.class));
    }

    @Test
    void missingProfilePhoneFailsBeforeInsert() {
        resident.setPhone(null);
        assertThatThrownBy(this::create).isInstanceOf(BusinessException.class);
        verify(reservationMapper, never()).insert(any(ResourceReservation.class));
    }
}
