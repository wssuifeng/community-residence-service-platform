package com.community.residence.common.reservation.service;

import com.community.residence.common.reservation.constant.ReservationStatus;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** G-016：完成须等待预约结束，拒绝不得释放占用，原状态与小区权限约束仍生效。 */
@ExtendWith(MockitoExtension.class)
class ReservationCompletionTimeTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Mock private ResourceReservationMapper reservationMapper;
    @InjectMocks private ReservationService service;
    private ResourceReservation reservation;

    @BeforeEach
    void prepare() {
        reservation = new ResourceReservation();
        reservation.setId(8L);
        reservation.setCommunityId(14L);
        reservation.setStatus(ReservationStatus.RESERVED);
        when(reservationMapper.selectByIdForUpdate(8L)).thenReturn(reservation);
    }

    @Test
    void futureDayCannotComplete() {
        assertNotEnded(NOW.plusDays(3));
    }

    @Test
    void todayBeforeEndCannotComplete() {
        assertNotEnded(NOW.plusSeconds(1));
    }

    @Test
    void previousDayCanCompleteEvenWithLaterTimeOfDay() {
        assertCanComplete(NOW.minusDays(1).withHour(23));
    }

    @Test
    void todayAfterEndCanComplete() {
        assertCanComplete(NOW.minusSeconds(1));
    }

    @Test
    void exactEndTimeCanComplete() {
        assertCanComplete(NOW);
    }

    @Test
    void expiredPendingReservationStillCannotComplete() {
        setEnd(NOW.minusDays(1));
        reservation.setStatus(ReservationStatus.PENDING);
        try (var security = mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> service.complete(8L, "done"))
                    .isInstanceOf(BusinessException.class);
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
            verify(reservationMapper, never()).updateById(any(ResourceReservation.class));
        }
    }

    @Test
    void expiredReservationOutsideCommunityStillDenied() {
        setEnd(NOW.minusDays(1));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.checkCommunityAccess(14L))
                    .thenThrow(new ForbiddenException("outside community"));
            assertThatThrownBy(() -> service.complete(8L, "done"))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            verify(reservationMapper, never()).updateById(any(ResourceReservation.class));
        }
    }

    private void setEnd(LocalDateTime end) {
        reservation.setReserveDate(end.toLocalDate());
        reservation.setStartTime(end.toLocalTime().minusMinutes(30));
        reservation.setEndTime(end.toLocalTime());
    }

    private void assertNotEnded(LocalDateTime end) {
        setEnd(end);
        try (var security = mockStatic(SecurityUtils.class);
             var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(NOW);
            assertThatThrownBy(() -> service.complete(8L, "done"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STATE_TRANSITION_INVALID);
                        assertThat(error.getMessage()).contains("预约时段尚未结束");
                    });
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            verify(reservationMapper, never()).updateById(any(ResourceReservation.class));
        }
    }

    private void assertCanComplete(LocalDateTime end) {
        setEnd(end);
        try (var security = mockStatic(SecurityUtils.class);
             var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(NOW);
            service.complete(8L, "done");
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
            verify(reservationMapper).updateById(reservation);
        }
    }
}
