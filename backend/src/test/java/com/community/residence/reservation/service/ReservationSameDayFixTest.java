package com.community.residence.reservation.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.vo.ReservationVO;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DEF-002 修复回归（R33/接口设计 §9.7.1.1 口径）：
 * 冲突拦截从「同天」放宽为「同居民同资源同时段重叠」——同天不重叠时段放行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预约同天误拦修复回归（DEF-002）")
class ReservationSameDayFixTest {

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
    private ReservationService service;

    private PublicResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        resource = new PublicResource();
        resource.setId(1L);
        resource.setCommunityId(1L);
        resource.setCapacity(10);
        lenient().when(resourceMapper.selectById(1L)).thenReturn(resource);
        lenient().when(timeslotMapper.selectList(any()))
                .thenReturn(List.of(template(LocalTime.of(8, 0), LocalTime.of(18, 0))));
        lenient().when(residentMapper.selectById(anyLong()))
                .thenReturn(new com.community.residence.resident.entity.Resident());
    }

    @Test
    @DisplayName("同天不同时段（不重叠）：放行（原误拒 5002「同一天已预约」）")
    void sameDay_differentSlot_ok() {
        /* 第 1 次 selectCount = 本人重叠检查 0；第 2 次 = 全局重叠检查 0 */
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        lenient().when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        ReservationVO vo = invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));
        assertThat(vo.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("同天同时段重叠（本人已有）：拒绝 DATA_EXISTS")
    void sameDay_overlappingMine_throws() {
        /* 第 1 次 selectCount = 本人重叠检查命中 1 */
        when(reservationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_EXISTS))
                .hasMessageContaining("同一时段");
    }

    @Test
    @DisplayName("首尾相接（本人上一场 10-11，本场 11-12）：放行（边界不算重叠）")
    void sameDay_adjacentSlot_ok() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        lenient().when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        assertThatCode(() -> invokeCreate(LocalTime.of(11, 0), LocalTime.of(12, 0)))
                .doesNotThrowAnyException();
    }

    private ReservationVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = org.mockito.Mockito.mockStatic(
                com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            var dto = new com.community.residence.reservation.dto.CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(LocalDate.now().plusDays(7));
            dto.setStartTime(start);
            dto.setEndTime(end);
            dto.setContactPhone("13800001111");
            return service.create(dto);
        }
    }

    /** 全天模板（08:00-18:00），覆盖同天任意时段组合 */
    private ResourceTimeslot template(LocalTime start, LocalTime end) {
        ResourceTimeslot t = new ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(LocalDate.now().plusDays(7).getDayOfWeek().getValue());
        t.setStartTime(start);
        t.setEndTime(end);
        t.setIsAvailable(1);
        return t;
    }
}
