package com.community.residence.reservation.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.entity.ResourceReservation;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-051/DEF-053 修复回归（R37 预约日期窗口服务端权威）：
 * 过去日期/今日已结束时段拒绝（endTime<=now 口径对齐 DEF-045 看房预约），
 * 最多提前 7 天（today+7 当日含边界可约）。前端日期控件只能挡常规入口，
 * API 直调必须被服务端拦截（拒绝发生在任何落库动作之前）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("预约日期窗口校验回归（DEF-051/053）")
class ReservationDateWindowFixTest {

    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private com.community.residence.reservation.mapper.ViolationRecordMapper violationRecordMapper;
    @Mock
    private PublicResourceMapper resourceMapper;
    @Mock
    private ResourceTimeslotMapper timeslotMapper;
    @Mock
    private com.community.residence.housing.mapper.ViewingAppointmentMapper viewingAppointmentMapper;
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
        /* DEF-062 跨域日程冲突检查（无跨域看房预约，走通正常路径） */
        lenient().when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);
        resource = new PublicResource();
        resource.setId(1L);
        resource.setCommunityId(1L);
        resource.setCapacity(10);
        lenient().when(resourceMapper.selectById(1L)).thenReturn(resource);
        lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
    }

    @Test
    @DisplayName("过去日期（today-1）：拒绝 5403，且不产生任何落库动作")
    void create_pastDate_rejected() {
        CreateReservationDTO dto = dto(LocalDate.now().minusDays(1),
                LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_PAST_SLOT))
                .hasMessageContaining("过去日期");
        assertThat(ErrorCode.RESERVATION_PAST_SLOT.getCode()).isEqualTo(5403);
        verify(reservationMapper, never()).insert(any(ResourceReservation.class));
    }

    @Test
    @DisplayName("超过 7 天（today+8）：拒绝 5404「最多可提前 7 天预约」")
    void create_beyondSevenDays_rejected() {
        CreateReservationDTO dto = dto(LocalDate.now().plusDays(8),
                LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_DATE_LIMIT))
                .hasMessageContaining("最多可提前 7 天预约");
        assertThat(ErrorCode.RESERVATION_DATE_LIMIT.getCode()).isEqualTo(5404);
        verify(reservationMapper, never()).insert(any(ResourceReservation.class));
    }

    @Test
    @DisplayName("恰好第 7 天（today+7，含边界）：放行")
    void create_exactlySeventhDay_ok() {
        LocalDate seventhDay = LocalDate.now().plusDays(7);
        when(timeslotMapper.selectList(any()))
                .thenReturn(List.of(template(seventhDay, LocalTime.of(8, 0), LocalTime.of(18, 0))));
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        ReservationVO vo = invokeCreate(seventhDay, LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("今日已结束时段（endTime<=now）：拒绝 5403「已过去的时段」")
    void create_todayPastSlot_rejected() {
        /* 00:00~00:01 死区内无法构造 start<end<=now 的必然过去时段，其余时刻均可：
           00:31 后用固定 00:00~00:30；00:01~00:31 用 00:00~当前分钟 */
        LocalTime now = LocalTime.now();
        assumeThat(now.isAfter(LocalTime.of(0, 1)))
                .as("跨午夜死区（00:00~00:01）无法构造必过去时段，跳过").isTrue();
        LocalTime start = LocalTime.of(0, 0);
        LocalTime end = now.isAfter(LocalTime.of(0, 30))
                ? LocalTime.of(0, 30)
                : now.truncatedTo(ChronoUnit.MINUTES);
        CreateReservationDTO dto = dto(LocalDate.now(), start, end);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_PAST_SLOT))
                .hasMessageContaining("已过去的时段");
        verify(reservationMapper, never()).insert(any(ResourceReservation.class));
    }

    @Test
    @DisplayName("今日未来时段（30 分钟栅格对齐）：放行")
    void create_todayFutureSlot_ok() {
        /* 22:00 后今天已无安全的 30 分钟对齐未来栅格（跨午夜窗口不稳定），跳过 */
        assumeThat(LocalTime.now().getHour()).as("跨午夜时段用例不稳定，22:00 后跳过")
                .isLessThanOrEqualTo(21);
        LocalTime start = LocalTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(2);
        LocalTime end = start.plusMinutes(30);
        when(timeslotMapper.selectList(any()))
                .thenReturn(List.of(template(LocalDate.now(), LocalTime.of(0, 0), LocalTime.of(23, 59))));
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        ReservationVO vo = invokeCreate(LocalDate.now(), start, end);

        assertThat(vo.getStatus()).isEqualTo(ReservationStatus.PENDING);
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
