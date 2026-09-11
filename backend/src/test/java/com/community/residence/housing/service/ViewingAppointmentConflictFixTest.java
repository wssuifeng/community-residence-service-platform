package com.community.residence.housing.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * DEF-007/008 修复回归（R55 出口红线）：并发竞态与半重叠拦截，
 * 判定矩阵与 C7（ReservationConflictFixTest）同口径（TC-SP-004）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看房预约冲突修复回归（DEF-007/008）")
class ViewingAppointmentConflictFixTest {

    /** 既有预约 A：10:00-12:00（占用中），TC-SP-004 前置 */
    private static final LocalTime A_START = LocalTime.of(10, 0);
    private static final LocalTime A_END = LocalTime.of(12, 0);

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

    @InjectMocks
    private ViewingAppointmentService service;

    private Housing housing;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
    }

    /* ---- TC-SP-004 边界对：重叠判定矩阵（DEF-008，原仅匹配 start_time 相同） ---- */

    @Test
    @DisplayName("半重叠（后）11:00-13:00 拦截（原仅 start 相同才拦，放行）")
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
    @DisplayName("尾首相接 08:00-10:00 放行（原因 start=10:00 被误拦，口径纠正）")
    void adjacent_before_ok() {
        assertNoConflict(LocalTime.of(8, 0), LocalTime.of(10, 0));
    }

    /* ---- DEF-007：并发竞态 ---- */

    @Test
    @DisplayName("并发竞态：selectCount 打满竞态窗口，锁内串行仅 1 路成功")
    void concurrent_racesWindow_onlyOneSucceeds() throws Exception {
        RLock rLock = new com.community.residence.reservation.service.JdkSynchronizedRLock(new Object());
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(housingMapper.selectById(1L)).thenReturn(housing);
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));

        AtomicLong insertCount = new AtomicLong();
        when(appointmentMapper.selectCount(any())).thenAnswer(inv -> {
            Thread.sleep(50);
            return insertCount.get();
        });
        when(appointmentMapper.insert(any(com.community.residence.housing.entity.ViewingAppointment.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    insertCount.incrementAndGet();
                    return 1;
                });

        int threads = 10;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var conflict = new java.util.concurrent.atomic.AtomicInteger();
        Runnable task = () -> {
            try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
                mocked.when(com.community.residence.common.context.SecurityUtils::getUser)
                        .thenReturn(null);
                var dto = dto(A_START, A_END);
                dto.setVisitorName("游客" + (long) Thread.currentThread().getId());
                try {
                    service.create(dto);
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
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(com.community.residence.housing.entity.ViewingAppointment.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_viewing_slot"));

        assertThatThrownBy(() -> invokeCreate(A_START, A_END))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT));
    }

    /* ---- 通用脚手架 ---- */

    private void assertConflict(LocalTime start, LocalTime end) {
        when(appointmentMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> invokeCreate(start, end))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT))
                .hasMessageContaining("重叠");
    }

    private void assertNoConflict(LocalTime start, LocalTime end) {
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        ViewingAppointmentVO vo = invokeCreate(start, end);
        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
    }

    /** 游客路径创建（SecurityUtils.getUser 返回 null 走 visitor 分支） */
    private ViewingAppointmentVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUser).thenReturn(null);
            when(housingMapper.selectById(1L)).thenReturn(housing);
            when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
            return service.create(dto(start, end));
        }
    }

    private CreateViewingAppointmentDTO dto(LocalTime start, LocalTime end) {
        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setHousingId(1L);
        dto.setAppointmentDate(date());
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setContactPhone("13800001111");
        dto.setVisitorName("游客甲");
        return dto;
    }

    /** 模板覆盖 08:00-14:00，覆盖全部边界对 */
    private HousingTimeslot template() {
        HousingTimeslot t = new HousingTimeslot();
        t.setId(1L);
        t.setHousingId(1L);
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
