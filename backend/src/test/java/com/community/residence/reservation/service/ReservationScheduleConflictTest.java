package com.community.residence.reservation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.vo.ReservationVO;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-062 修复回归（预约人日程冲突，「影分身」拦截）：同一账号同日与
 * 任意资源预约（跨资源，uk_reservation_user_slot 仅防同资源同槽）或
 * 任意看房预约（跨域）时间区间重叠即拒 5406；不重叠/非活跃记录放行。
 * 落库前的校验不与本人新纪录比对（本条尚未 INSERT）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("资源预约日程冲突回归（DEF-062）")
class ReservationScheduleConflictTest {

    private static final long RESIDENT_ID = 2L;

    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private PublicResourceMapper resourceMapper;
    @Mock
    private ResourceTimeslotMapper timeslotMapper;
    @Mock
    private ViewingAppointmentMapper viewingAppointmentMapper;
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

    private PublicResource resource;

    /* lambda 列解析依赖 TableInfo（wrapper.getSqlSegment() 物化参数需要） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ResourceReservation.class);
        TableInfoHelper.initTableInfo(assistant, ViewingAppointment.class);
    }

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
        resource.setName("会议室");
        resource.setCapacity(10);
        lenient().when(residentMapper.selectById(anyLong()))
                .thenReturn(new com.community.residence.resident.entity.Resident());
    }

    @Test
    @DisplayName("跨资源同刻：同账号他资源 PENDING 预约 10:00-11:00，再约本资源 10:00-11:00 拒 5406")
    void create_crossResourceSameTime_throws5406() {
        /* reservationMapper.selectCount 两次调用：第 1 次 = 本人同资源重叠检查（0），
           第 2 次 = 跨资源日程冲突（命中他资源预约 1）→ 在跨域查询前拦截；
           跨域看房无预约（本用例不触达，lenient 声明语义） */
        when(reservationMapper.selectCount(any())).thenReturn(0L, 1L);
        lenient().when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_SCHEDULE_CONFLICT))
                .hasMessageContaining("该时间段您已有其他预约安排");
    }

    @Test
    @DisplayName("跨域同刻（C7→C12）：同账号既有看房预约 10:00-11:00，再约资源拒 5406")
    void create_crossDomainViewingSameTime_throws5406() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(viewingAppointmentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_SCHEDULE_CONFLICT))
                .hasMessageContaining("请调整时间");
    }

    @Test
    @DisplayName("同日不重叠：既有预约 13:00-14:00，约 10:00-11:00 放行（首尾相接不算重叠）")
    void create_sameDayNonOverlap_ok() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        ReservationVO vo = invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("已取消/已违约记录不算冲突：日程查询带活跃状态集过滤（PENDING/RESERVED）放行")
    void create_cancelledOrViolatedNotConflicting() {
        /* 既有夹具为本人 CANCELLED/VIOLATED 的他资源预约——日程查询须带
           PENDING/RESERVED 活跃集 IN 条件才会在 SQL 侧滤掉它们（映射到
           selectCount=0）；下方 ArgumentCaptor 断言查询确实携带活跃集过滤 */
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(reservationMapper.insert(any(ResourceReservation.class))).thenReturn(1);

        ReservationVO vo = invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("PENDING");
        /* 跨资源日程查询（第 2 次 selectCount）带 PENDING/RESERVED 活跃集，
           不含 CANCELLED/VIOLATED 非活跃状态（否则已取消记录会被误判冲突）。
           参数在 SQL 渲染时物化，须先调 getSqlSegment() 再读 paramNameValuePairs */
        org.mockito.ArgumentCaptor<
                LambdaQueryWrapper<ResourceReservation>> reservationCaptor = wrapperCaptor();
        verify(reservationMapper, org.mockito.Mockito.times(2))
                .selectCount(reservationCaptor.capture());
        List<LambdaQueryWrapper<ResourceReservation>> reservationWrappers =
                reservationCaptor.getAllValues();
        reservationWrappers.forEach(LambdaQueryWrapper::getSqlSegment);
        Collection<Object> reservationValues =
                reservationWrappers.get(1).getParamNameValuePairs().values();
        assertThat(reservationValues).contains("PENDING", "RESERVED");
        assertThat(reservationValues).doesNotContain("CANCELLED", "VIOLATED");
        /* 跨域看房查询带 TO_CONFIRM/RESERVED 活跃集 */
        org.mockito.ArgumentCaptor<
                LambdaQueryWrapper<ViewingAppointment>> viewingCaptor = wrapperCaptor();
        verify(viewingAppointmentMapper).selectCount(viewingCaptor.capture());
        viewingCaptor.getValue().getSqlSegment();
        Collection<Object> viewingValues =
                viewingCaptor.getValue().getParamNameValuePairs().values();
        assertThat(viewingValues).contains("TO_CONFIRM", "RESERVED");
        assertThat(viewingValues).doesNotContain("CANCELLED", "VIOLATED");
    }

    /** LambdaQueryWrapper 捕获器（泛型擦除，Class 直取） */
    @SuppressWarnings("unchecked")
    private static <T> org.mockito.ArgumentCaptor<LambdaQueryWrapper<T>> wrapperCaptor() {
        return org.mockito.ArgumentCaptor.forClass(
                (Class<LambdaQueryWrapper<T>>) (Class<?>) LambdaQueryWrapper.class);
    }

    /* ---- 脚手架 ---- */

    private ReservationVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                    .thenReturn(RESIDENT_ID);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
            var dto = new CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(date());
            dto.setStartTime(start);
            dto.setEndTime(end);
            dto.setContactPhone("13800001111");
            return reservationService.create(dto);
        }
    }

    /** 模板覆盖 08:00-18:00，请求区间必然被并集覆盖 */
    private ResourceTimeslot template() {
        ResourceTimeslot t = new ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(date().getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(8, 0));
        t.setEndTime(LocalTime.of(18, 0));
        t.setIsAvailable(1);
        return t;
    }

    private LocalDate date() {
        return LocalDate.now().plusDays(7);
    }
}
