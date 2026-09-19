package com.community.residence.reservation.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.vo.ReservationVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

/**
 * R60 连续时长上限回归（资源预约域）：sys_config reservation.max_continuous_minutes
 * （缺省 120）服务端权威校验——API 直调合并连续时段的超长请求被拒（消息含上限值），
 * 恰好 120 分钟通过；配置覆盖生效。另含相邻模板并集区间覆盖（连续多段合并提交）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("资源预约连续时长上限回归（R60）")
class ReservationContinuousLimitTest {

    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private com.community.residence.reservation.mapper.ViolationRecordMapper violationRecordMapper;
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
        lenient().when(residentMapper.selectById(any())).thenReturn(new Resident());
    }

    @Test
    @DisplayName("超限 150 分钟（10:00-12:30）：拒绝 5405，消息含上限值，不落库")
    void create_overLimit_rejected() {
        assertThatThrownBy(() -> invokeCreate(LocalDate.now().plusDays(3),
                LocalTime.of(10, 0), LocalTime.of(12, 30)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_DURATION_LIMIT))
                .hasMessageContaining("单次预约最长 120 分钟");
        assertThat(ErrorCode.RESERVATION_DURATION_LIMIT.getCode()).isEqualTo(5405);
        verify(reservationMapper, never()).insert(any(com.community.residence.reservation.entity.ResourceReservation.class));
    }

    @Test
    @DisplayName("恰好 120 分钟（10:00-12:00）：放行")
    void create_exactlyLimit_ok() {
        stubCreateSuccess(List.of(template(LocalDate.now().plusDays(3),
                LocalTime.of(8, 0), LocalTime.of(18, 0))));

        ReservationVO vo = invokeCreate(LocalDate.now().plusDays(3),
                LocalTime.of(10, 0), LocalTime.of(12, 0));

        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("上限可配生效：sys_config=30 时 60 分钟请求亦被拒（消息含 30）")
    void create_configuredLimit_enforced() {
        when(sysConfigService.getValue("reservation.max_continuous_minutes")).thenReturn("30");

        assertThatThrownBy(() -> invokeCreate(LocalDate.now().plusDays(3),
                LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_DURATION_LIMIT))
                .hasMessageContaining("单次预约最长 30 分钟");
        verifyNoInteractions(lock);
    }

    @Test
    @DisplayName("连续多段合并提交：相邻模板 09:00-10:00 + 10:00-11:00 覆盖 09:00-11:00 通过")
    void create_adjacentTemplatesMerged_ok() {
        LocalDate date = LocalDate.now().plusDays(3);
        stubCreateSuccess(List.of(
                template(date, LocalTime.of(9, 0), LocalTime.of(10, 0)),
                template(date, LocalTime.of(10, 0), LocalTime.of(11, 0))));

        ReservationVO vo = invokeCreate(date, LocalTime.of(9, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    /* ---- 脚手架 ---- */

    private void stubCreateSuccess(List<ResourceTimeslot> templates) {
        when(timeslotMapper.selectList(any())).thenReturn(templates);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(reservationMapper.insert(any(com.community.residence.reservation.entity.ResourceReservation.class)))
                .thenReturn(1);
    }

    private ReservationVO invokeCreate(LocalDate date, LocalTime start, LocalTime end) {
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);
            return service.create(dto(date, start, end));
        }
    }

    private CreateReservationDTO dto(LocalDate date, LocalTime start, LocalTime end) {
        CreateReservationDTO dto = new CreateReservationDTO();
        dto.setResourceId(1L);
        dto.setReserveDate(date);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setContactPhone("13800001111");
        return dto;
    }

    private ResourceTimeslot template(LocalDate date, LocalTime start, LocalTime end) {
        ResourceTimeslot t = new ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(date.getDayOfWeek().getValue());
        t.setStartTime(start);
        t.setEndTime(end);
        t.setIsAvailable(1);
        return t;
    }
}
