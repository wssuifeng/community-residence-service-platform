package com.community.residence.reservation.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Slot Grid 栅格化修复回归（08 §3.7 定案，第三批第 13 项 + D-端点4 V11）：
 * 对齐校验、逐格容量、多用户同槽、边界对（首尾相接/存量非对齐覆盖计入）、
 * 并发竞态（容量 N 多人抢同槽恰好 N 路成功）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Slot Grid 栅格化回归（第三批第 13 项）")
class SlotGridFixTest {

    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
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
    private ReservationService reservationService;

    private PublicResource resource;
    private ResourceTimeslot template;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        /* DEF-062 跨域日程冲突检查（无跨域看房预约，聚焦栅格矩阵） */
        lenient().when(viewingAppointmentMapper.selectCount(any())).thenReturn(0L);
        resource = new PublicResource();
        resource.setId(1L);
        resource.setCommunityId(1L);
        resource.setName("会议室");
        resource.setCapacity(3);
        resource.setSlotUnit(30);

        template = new ResourceTimeslot();
        template.setId(1L);
        template.setResourceId(1L);
        template.setCommunityId(1L);
        template.setDayOfWeek(date().getDayOfWeek().getValue());
        template.setStartTime(LocalTime.of(9, 0));
        template.setEndTime(LocalTime.of(12, 0));
        template.setIsAvailable(1);
    }

    private LocalDate date() {
        return LocalDate.now().plusDays(7);
    }

    /* ---- 防线①：对齐校验 ---- */

    @Test
    @DisplayName("非对齐起止拒绝 400（10:15-11:00 非 30 分钟整数倍）")
    void create_misalignedStart_throws400() {
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 15), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("整数倍");
    }

    @Test
    @DisplayName("非对齐结束拒绝 400（10:00-11:10）")
    void create_misalignedEnd_throws400() {
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM));
    }

    @Test
    @DisplayName("15 分钟粒度资源：对齐 15 分钟倍数放行")
    void create_alignedTo15Minutes_ok() {
        resource.setSlotUnit(15);
        lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        ReservationVO vo = invokeCreate(LocalTime.of(10, 15), LocalTime.of(10, 45));
        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("60 分钟粒度资源：30 分钟对齐请求拒绝")
    void create_slotUnit60_rejects30Align() {
        resource.setSlotUnit(60);
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(10, 30)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("整数倍");
    }

    /* ---- 防线②：锁内逐格容量 ---- */

    @Test
    @DisplayName("多用户同槽放行至 capacity（容量 3、已占 2 → 第 3 人成功）")
    void create_multiUserWithinCapacity_ok() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                reservationOf(12L, LocalTime.of(10, 0), LocalTime.of(10, 30))));
        lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
        ReservationVO vo = invokeCreate(LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("容量满拒 5401（容量 3、已占 3 → 满员拒绝）")
    void create_capacityExceeded_throws() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                reservationOf(12L, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                reservationOf(13L, LocalTime.of(10, 0), LocalTime.of(10, 30))));
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(10, 30)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT))
                .hasMessageContaining("已约满");
    }

    @Test
    @DisplayName("存量非对齐预约覆盖即占用：10:10-10:20 预约计入 10:00 与 10:30 两格")
    void create_legacyMisaligned_coversBothGrids() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 10), LocalTime.of(10, 20)),
                reservationOf(12L, LocalTime.of(10, 10), LocalTime.of(10, 20))));
        /* 容量 3、两格各已计 2：请求 10:00-10:30 仍可（各格 2<3） */
        lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
        ReservationVO vo = invokeCreate(LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("边界对：首尾相接放行（已有 10:00-10:30，请求 10:30-11:00）")
    void create_adjacentGrid_ok() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 0), LocalTime.of(10, 30))));
        lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
        ReservationVO vo = invokeCreate(LocalTime.of(10, 30), LocalTime.of(11, 0));
        assertThat(vo.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("跨格请求逐格检查：请求 10:00-11:00，10:30-11:00 格满员拒绝")
    void create_multiGridRequest_anyFullGridRejects() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 30), LocalTime.of(11, 0)),
                reservationOf(12L, LocalTime.of(10, 30), LocalTime.of(11, 0)),
                reservationOf(13L, LocalTime.of(10, 30), LocalTime.of(11, 0))));
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT))
                .hasMessageContaining("10:30");
    }

    /* ---- 并发：容量 3 同槽 5 路并发 → 恰 3 成功 2 拒绝 ---- */

    @Test
    @DisplayName("并发竞态：容量 3 同槽 5 路 → 恰 3 路成功（锁内串行逐格计数可见）")
    void concurrent_capacity3_fiveThreads_exactlyThreeSucceed() throws Exception {
        Object jdkLock = new Object();
        RLock rLock = new JdkSynchronizedRLock(jdkLock);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template));

        List<ResourceReservation> inserted = Collections.synchronizedList(new ArrayList<>());
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.selectList(any())).thenAnswer(inv -> List.copyOf(inserted));
        when(reservationMapper.insert(any(ResourceReservation.class))).thenAnswer(inv -> {
            ResourceReservation saved = inv.getArgument(0, ResourceReservation.class);
            saved.setId(1000L + inserted.size() + 1);
            inserted.add(saved);
            return 1;
        });

        int threads = 5;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final long userId = 100L + i;
            new Thread(() -> {
                try (var mocked = org.mockito.Mockito.mockStatic(
                        com.community.residence.common.context.SecurityUtils.class)) {
                    mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                            .thenReturn(userId);
                    var dto = new CreateReservationDTO();
                    dto.setResourceId(1L);
                    dto.setReserveDate(date());
                    dto.setStartTime(LocalTime.of(10, 0));
                    dto.setEndTime(LocalTime.of(10, 30));
                    dto.setContactPhone("13800001111");
                    try {
                        reservationService.create(dto);
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.RESERVATION_CONFLICT) {
                            conflict.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        Thread.sleep(200);

        assertThat(success.get()).as("容量 3 恰 3 路成功").isEqualTo(3);
        assertThat(conflict.get()).as("其余 2 路满员拒绝").isEqualTo(2);
        assertThat(inserted.size()).isEqualTo(3);
    }

    /* ---- available-slots 栅格分桶与排序 ---- */

    @Test
    @DisplayName("available-slots：模板 9:00-12:00 按 30 分钟拆 6 格，同日按 startTime 排序")
    void availableSlots_gridBucketedAndSorted() {
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(template));
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(10, 0), LocalTime.of(10, 30))));

        var slots = reservationService.availableSlots(1L, date(), date());

        assertThat(slots).hasSize(6);
        assertThat(slots.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(slots.get(0).getEndTime()).isEqualTo(LocalTime.of(9, 30));
        /* 10:00 格 booked=1；其余格 0；maxBookings=capacity=3 */
        var grid10 = slots.stream()
                .filter(s -> s.getStartTime().equals(LocalTime.of(10, 0))).findFirst().orElseThrow();
        assertThat(grid10.getCurrentBookings()).isEqualTo(1);
        assertThat(grid10.getMaxBookings()).isEqualTo(3);
        assertThat(grid10.getStatus()).isEqualTo("AVAILABLE");
        /* 同日排序：startTime 单调不降 */
        for (int i = 1; i < slots.size(); i++) {
            assertThat(slots.get(i).getStartTime())
                    .isAfterOrEqualTo(slots.get(i - 1).getStartTime());
        }
    }

    @Test
    @DisplayName("available-slots：栅格格满时状态 FULL")
    void availableSlots_fullGrid() {
        resource.setCapacity(1);
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(template));
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservationOf(11L, LocalTime.of(9, 0), LocalTime.of(9, 30))));

        var slots = reservationService.availableSlots(1L, date(), date());
        assertThat(slots.get(0).getStatus()).isEqualTo("FULL");
        assertThat(slots.get(1).getStatus()).isEqualTo("AVAILABLE");
    }

    /* ---- 脚手架 ---- */

    private ResourceReservation reservationOf(Long userId, LocalTime start, LocalTime end) {
        ResourceReservation r = new ResourceReservation();
        r.setUserId(userId);
        r.setResourceId(1L);
        r.setCommunityId(1L);
        r.setReserveDate(date());
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(ReservationStatus.RESERVED);
        return r;
    }

    private ReservationVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = org.mockito.Mockito.mockStatic(
                com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template));
            lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
            var dto = new CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(date());
            dto.setStartTime(start);
            dto.setEndTime(end);
            dto.setContactPhone("13800001111");
            return reservationService.create(dto);
        }
    }
}
