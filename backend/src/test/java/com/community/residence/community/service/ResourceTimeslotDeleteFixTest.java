package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-015 修复回归（R6 删除保护）：
 * 存在占用中预约（PENDING/RESERVED）的时段模板禁止删除（5106，对齐资源本体 5105）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("时段删除引用保护修复回归（DEF-015）")
class ResourceTimeslotDeleteFixTest {

    @Mock
    private ResourceTimeslotMapper timeslotMapper;
    @Mock
    private PublicResourceService publicResourceService;
    @Mock
    private ResourceReservationMapper reservationMapper;

    @InjectMocks
    private ResourceTimeslotService service;

    private ResourceTimeslot timeslot;

    @BeforeEach
    void setUp() {
        timeslot = new ResourceTimeslot();
        timeslot.setId(1L);
        timeslot.setResourceId(1L);
        timeslot.setCommunityId(1L);
        timeslot.setDayOfWeek(1);
        timeslot.setStartTime(LocalTime.of(9, 0));
        timeslot.setEndTime(LocalTime.of(10, 0));
        timeslot.setIsAvailable(1);
        when(timeslotMapper.selectById(1L)).thenReturn(timeslot);
    }

    @Test
    @DisplayName("有占用中预约的时段：删除被拒（5106，原 200 放行致预约悬空）")
    void delete_referencedByActiveReservation_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(reservationMapper.selectCount(any())).thenReturn(2L);

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TIMESLOT_HAS_RESERVATION));
            verify(timeslotMapper, never()).deleteById(1L);
        }
    }

    @Test
    @DisplayName("仅历史预约（已完成/取消/违约）的时段：删除放行（不阻碍模板维护）")
    void delete_onlyFinishedReservations_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(reservationMapper.selectCount(any())).thenReturn(0L);

            assertThatCode(() -> service.delete(1L)).doesNotThrowAnyException();
            verify(timeslotMapper).deleteById(1L);
        }
    }
}
