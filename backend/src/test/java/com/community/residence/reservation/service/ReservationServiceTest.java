package com.community.residence.reservation.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.entity.ViolationRecord;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 资源预约测试：冲突检测、容量校验（排除自身）、状态机、违约留痕 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 单元测试")
class ReservationServiceTest {

    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private PublicResourceMapper resourceMapper;
    @Mock
    private ResourceTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private ReservationService reservationService;

    private com.community.residence.community.entity.PublicResource resource;
    private ResourceReservation pendingReservation;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        resource = new com.community.residence.community.entity.PublicResource();
        resource.setId(1L);
        resource.setCommunityId(1L);
        resource.setName("Gym");
        resource.setCapacity(1);

        pendingReservation = new ResourceReservation();
        pendingReservation.setId(1L);
        pendingReservation.setUserId(1L);
        pendingReservation.setResourceId(1L);
        pendingReservation.setCommunityId(1L);
        pendingReservation.setReserveDate(LocalDate.now().plusDays(7));
        pendingReservation.setStartTime(LocalTime.of(9, 0));
        pendingReservation.setEndTime(LocalTime.of(10, 0));
        pendingReservation.setStatus(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("冲突检测：同一居民同资源同日重复预约拒绝（DATA_EXISTS）")
    void create_duplicateDaily_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            when(timeslotMapper.selectList(any())).thenReturn(java.util.List.of(template()));
            when(reservationMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> reservationService.create(dto()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可重复预约");
        }
    }

    @Test
    @DisplayName("容量校验：时段满员拒绝（RESERVATION_CONFLICT 5401）")
    void create_capacityFull_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            when(timeslotMapper.selectList(any())).thenReturn(java.util.List.of(template()));
            // 第一次 count：本人重复预约检查 = 0；第二次：容量检查 = 1（满）
            when(reservationMapper.selectCount(any())).thenReturn(0L).thenReturn(1L);

            assertThatThrownBy(() -> reservationService.create(dto()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.RESERVATION_CONFLICT));
        }
    }

    /** 时段模板（覆盖 09:00-10:00，星期与预约日一致） */
    private com.community.residence.community.entity.ResourceTimeslot template() {
        com.community.residence.community.entity.ResourceTimeslot t =
                new com.community.residence.community.entity.ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(pendingReservation.getReserveDate().getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(9, 0));
        t.setEndTime(LocalTime.of(10, 0));
        t.setIsAvailable(1);
        return t;
    }

    @Test
    @DisplayName("状态机：PENDING 不可直达 COMPLETED")
    void complete_fromPending_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(reservationMapper.selectById(1L)).thenReturn(pendingReservation);
            assertThatThrownBy(() -> reservationService.complete(1L, "done"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("COMPLETED");
        }
    }

    @Test
    @DisplayName("违约处置：RESERVED → VIOLATED 且写违约记录")
    void violate_writesViolationRecord() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            pendingReservation.setStatus(ReservationStatus.RESERVED);
            when(reservationMapper.selectById(1L)).thenReturn(pendingReservation);
            when(reservationMapper.updateById(any(com.community.residence.reservation.entity.ResourceReservation.class))).thenReturn(1);
            when(violationRecordMapper.insert(any(ViolationRecord.class))).thenReturn(1);
            when(violationRecordMapper.selectCount(any())).thenReturn(1L);
            org.mockito.Mockito.lenient().when(sysConfigService.getValue(any())).thenReturn("3");
            org.mockito.Mockito.lenient().when(residentMapper.selectById(1L))
                    .thenReturn(new com.community.residence.resident.entity.Resident());
            org.mockito.Mockito.lenient().when(residentMapper.updateById(
                    any(com.community.residence.resident.entity.Resident.class))).thenReturn(1);

            reservationService.violate(1L, "no show");

            assertThat(pendingReservation.getStatus()).isEqualTo("VIOLATED");
            org.mockito.Mockito.verify(violationRecordMapper).insert(any(ViolationRecord.class));
        }
    }

    private CreateReservationDTO dto() {
        CreateReservationDTO dto = new CreateReservationDTO();
        dto.setResourceId(1L);
        dto.setReserveDate(LocalDate.now().plusDays(7));
        dto.setStartTime(LocalTime.of(9, 0));
        dto.setEndTime(LocalTime.of(10, 0));
        dto.setContactPhone("13800001111");
        return dto;
    }
}
