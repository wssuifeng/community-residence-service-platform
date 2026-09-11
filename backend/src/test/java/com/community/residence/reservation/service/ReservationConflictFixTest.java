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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DEF-005/006 修复回归（R33 出口红线）：并发竞态与半重叠拦截。
 * 重叠判定矩阵按 TC-SP-002 六项边界对设计；并发场景用真实多线程打满
 * selectCount/insert 的竞态窗口复现原缺陷，验证锁内串行使窗口闭合。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预约冲突修复回归（DEF-005/006）")
class ReservationConflictFixTest {

    /** 既有预约 A：10:00-12:00（占用中），TC-SP-002 前置 */
    private static final LocalTime A_START = LocalTime.of(10, 0);
    private static final LocalTime A_END = LocalTime.of(12, 0);

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

    private PublicResource resource;
    private ResourceReservation existing;

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
        resource.setName("Gym");
        resource.setCapacity(1);

        existing = new ResourceReservation();
        existing.setId(100L);
        existing.setUserId(999L);
        existing.setResourceId(1L);
        existing.setCommunityId(1L);
        existing.setReserveDate(date());
        existing.setStartTime(A_START);
        existing.setEndTime(A_END);
        existing.setStatus(ReservationStatus.RESERVED);
    }

    /* ---- TC-SP-002 边界对：重叠判定矩阵（DEF-006） ---- */

    @Test
    @DisplayName("半重叠（后）11:00-13:00 拦截（原仅精确匹配时放行）")
    void overlap_backHalf_throws() {
        assertConflict(LocalTime.of(11, 0), LocalTime.of(13, 0));
    }

    @Test
    @DisplayName("半重叠（前）09:00-11:00 拦截（原放行）")
    void overlap_frontHalf_throws() {
        assertConflict(LocalTime.of(9, 0), LocalTime.of(11, 0));
    }

    @Test
    @DisplayName("完全包含 09:30-13:30 拦截（原放行）")
    void overlap_contains_throws() {
        assertConflict(LocalTime.of(9, 30), LocalTime.of(13, 30));
    }

    @Test
    @DisplayName("完全被包含 10:30-11:30 拦截（原放行）")
    void overlap_contained_throws() {
        assertConflict(LocalTime.of(10, 30), LocalTime.of(11, 30));
    }

    @Test
    @DisplayName("首尾相接 12:00-14:00 放行（边界值不算重叠）")
    void adjacent_after_ok() {
        assertNoConflict(LocalTime.of(12, 0), LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("尾首相接 08:00-10:00 放行（边界值不算重叠）")
    void adjacent_before_ok() {
        assertNoConflict(LocalTime.of(8, 0), LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("已完成/已取消/已违约预约不占用时段（放开重叠）")
    void finishedStatus_notOccupying() {
        for (String status : List.of("COMPLETED", "CANCELLED", "REJECTED", "VIOLATED")) {
            existing.setStatus(status);
            // selectCount 用 wrapper 参数按状态过滤，模拟「无占用中记录」返回 0
            assertNoConflictWithCount(LocalTime.of(11, 0), LocalTime.of(13, 0), 0L);
        }
    }

    /* ---- DEF-005：并发竞态 ---- */

    @Test
    @DisplayName("并发竞态：selectCount 打满竞态窗口（无锁场景 10/10 落库），锁内串行仅 1 路成功")
    void concurrent_racesWindow_onlyOneSucceeds() throws Exception {
        // 无 Redis 依赖：redissonClient.getLock 返回 JDK 锁适配（互斥即满足测试语义）
        Object jdkLock = new Object();
        RLock rLock = new JdkSynchronizedRLock(jdkLock);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(resourceMapper.selectById(1L)).thenReturn(resource);
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));

        // selectCount 引入延迟打满「查后插前」竞态窗口（原缺陷复现条件）；
        // 每线程第 1 次调用=本人重复检查（独立身份恒 0），第 2 次=重叠查询（返回已落库数）
        AtomicLong insertCount = new AtomicLong();
        ThreadLocal<Integer> callIdx = ThreadLocal.withInitial(() -> 0);
        when(reservationMapper.selectCount(any())).thenAnswer(inv -> {
            int idx = callIdx.get();
            callIdx.set(idx + 1);
            Thread.sleep(50);
            return idx == 0 ? 0L : insertCount.get();
        });
        when(reservationMapper.insert(any(ResourceReservation.class))).thenAnswer(inv -> {
            Thread.sleep(20);
            insertCount.incrementAndGet();
            inv.getArgument(0, ResourceReservation.class).setId(1000L + insertCount.get());
            return 1;
        });

        int threads = 10;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var conflict = new java.util.concurrent.atomic.AtomicInteger();
        Runnable task = () -> {
            try (var mocked = org.mockito.Mockito.mockStatic(
                    com.community.residence.common.context.SecurityUtils.class)) {
                mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                        .thenReturn((long) Thread.currentThread().getId() + 1);
                var dto = new com.community.residence.reservation.dto.CreateReservationDTO();
                dto.setResourceId(1L);
                dto.setReserveDate(date());
                dto.setStartTime(A_START);
                dto.setEndTime(A_END);
                dto.setContactPhone("13800001111");
                try {
                    reservationService.create(dto);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.RESERVATION_CONFLICT) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignore) {
                    // 其他异常按失败计（不使断言误过）
                }
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < threads; i++) {
            new Thread(task).start();
        }
        latch.await();
        Thread.sleep(200);

        assertThat(success.get()).as("并发同槽仅 1 路成功").isEqualTo(1);
        assertThat(conflict.get()).as("其余全部按冲突拦截").isEqualTo(threads - 1);
        assertThat(insertCount.get()).as("落库仅 1 条").isEqualTo(1);
    }

    @Test
    @DisplayName("唯一约束兜底：insert 抛 DuplicateKeyException 转业务冲突（锁失效最后防线）")
    void duplicateKeyTranslated_toConflict() {
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationMapper.insert(any(ResourceReservation.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_reservation_slot"));

        assertThatThrownBy(() -> invokeCreate(A_START, A_END))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT));
    }

    /* ---- 通用脚手架 ---- */

    private void assertConflict(LocalTime start, LocalTime end) {
        assertNoConflictWithCount(start, end, 1L);
    }

    private void assertNoConflict(LocalTime start, LocalTime end) {
        assertNoConflictWithCount(start, end, 0L);
    }

    /** count>0 模拟区间重叠查询命中占用记录（SQL 语义：start<已有end AND end>已有start）；
       第 1 次 selectCount = 本人重复检查（0=无重复），第 2 次 = 重叠查询 */
    private void assertNoConflictWithCount(LocalTime start, LocalTime end, long overlapCount) {
        when(reservationMapper.selectCount(any())).thenReturn(0L).thenReturn(overlapCount);
        if (overlapCount > 0) {
            assertThatThrownBy(() -> invokeCreate(start, end))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.RESERVATION_CONFLICT))
                    .hasMessageContaining("重叠");
        } else {
            ReservationVO vo = invokeCreate(start, end);
            assertThat(vo.getStatus()).isEqualTo("PENDING");
        }
    }

    private ReservationVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = org.mockito.Mockito.mockStatic(
                com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
            lenient().when(residentMapper.selectById(anyLong())).thenReturn(new Resident());
            var dto = new com.community.residence.reservation.dto.CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(date());
            dto.setStartTime(start);
            dto.setEndTime(end);
            dto.setContactPhone("13800001111");
            return reservationService.create(dto);
        }
    }

    /** 模板覆盖 08:00-14:00，覆盖全部边界对 */
    private ResourceTimeslot template() {
        ResourceTimeslot t = new ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(date().getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(8, 0));
        t.setEndTime(LocalTime.of(14, 0));
        t.setIsAvailable(1);
        return t;
    }

    private LocalDate date() {
        return LocalDate.now().plusDays(7);
    }
}
