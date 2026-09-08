package com.community.residence.reservation.service;

import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.entity.Resident;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 预约服务补充测试：创建成功、取消、拒绝、可预约时段展开 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 补充路径测试")
class ReservationServiceExtraTest {

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

    @InjectMocks
    private ReservationService reservationService;

    private PublicResource resource;
    private ResourceReservation reservation;

    @BeforeEach
    void setUp() {
        resource = new PublicResource();
        resource.setId(1L);
        resource.setCommunityId(1L);
        resource.setName("Gym");
        resource.setCapacity(2);

        reservation = new ResourceReservation();
        reservation.setId(1L);
        reservation.setUserId(1L);
        reservation.setResourceId(1L);
        reservation.setCommunityId(1L);
        reservation.setReserveDate(LocalDate.now().plusDays(7));
        reservation.setStartTime(LocalTime.of(9, 0));
        reservation.setEndTime(LocalTime.of(10, 0));
        reservation.setStatus(ReservationStatus.PENDING);
    }

    private ResourceTimeslot template(LocalDate date) {
        ResourceTimeslot t = new ResourceTimeslot();
        t.setId(1L);
        t.setResourceId(1L);
        t.setCommunityId(1L);
        t.setDayOfWeek(date.getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(9, 0));
        t.setEndTime(LocalTime.of(10, 0));
        t.setIsAvailable(1);
        return t;
    }

    @Test
    @DisplayName("创建成功：PENDING 状态 + 装配居民与资源名称")
    void create_success() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            LocalDate date = LocalDate.now().plusDays(7);
            when(timeslotMapper.selectList(any())).thenReturn(List.of(template(date)));
            when(reservationMapper.selectCount(any())).thenReturn(0L);
            when(reservationMapper.insert(any(ResourceReservation.class))).thenAnswer(inv -> {
                inv.getArgument(0, ResourceReservation.class).setId(9L);
                return 1;
            });
            Resident r = new Resident();
            r.setRealName("Zhang");
            when(residentMapper.selectById(1L)).thenReturn(r);

            var dto = new com.community.residence.reservation.dto.CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(date);
            dto.setStartTime(LocalTime.of(9, 0));
            dto.setEndTime(LocalTime.of(10, 0));
            dto.setContactPhone("13800001111");

            var vo = reservationService.create(dto);
            assertThat(vo.getStatus()).isEqualTo("PENDING");
            assertThat(vo.getResourceName()).isEqualTo("Gym");
            assertThat(vo.getUserName()).isEqualTo("Zhang");
        }
    }

    @Test
    @DisplayName("时段外预约：不在模板覆盖范围拒绝")
    void create_outsideTemplate_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            // 模板 14:00-15:00，预约 09:00-10:00
            ResourceTimeslot t = template(LocalDate.now().plusDays(7));
            t.setStartTime(LocalTime.of(14, 0));
            t.setEndTime(LocalTime.of(15, 0));
            when(timeslotMapper.selectList(any())).thenReturn(List.of(t));

            var dto = new com.community.residence.reservation.dto.CreateReservationDTO();
            dto.setResourceId(1L);
            dto.setReserveDate(LocalDate.now().plusDays(7));
            dto.setStartTime(LocalTime.of(9, 0));
            dto.setEndTime(LocalTime.of(10, 0));
            dto.setContactPhone("13800001111");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reservationService.create(dto))
                    .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
                    .hasMessageContaining("不在资源可预约时段内");
        }
    }

    @Test
    @DisplayName("取消预约：仅预约人本人可取消（他人取消 403）")
    void cancel_notOwner_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(999L);
            when(reservationMapper.selectById(1L)).thenReturn(reservation);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reservationService.cancel(1L, "change"))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("拒绝预约：PENDING → REJECTED")
    void reject_pending_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(reservationMapper.selectById(1L)).thenReturn(reservation);
            when(reservationMapper.updateById(any(ResourceReservation.class))).thenReturn(1);

            reservationService.reject(1L, "full");
            assertThat(reservation.getStatus()).isEqualTo("REJECTED");
            assertThat(reservation.getRemark()).isEqualTo("full");
        }
    }

    @Test
    @DisplayName("可预约时段展开：模板按日期展开 + 占用计数 + FULL 状态")
    void availableSlots_expandsAndCounts() {
        LocalDate monday = LocalDate.now().plusDays(1);
        while (monday.getDayOfWeek().getValue() != 1) {
            monday = monday.plusDays(1);
        }
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(template(monday)));
        ResourceReservation occupying = new ResourceReservation();
        occupying.setReserveDate(monday);
        occupying.setStartTime(LocalTime.of(9, 0));
        occupying.setEndTime(LocalTime.of(10, 0));
        occupying.setStatus(ReservationStatus.RESERVED);
        when(reservationMapper.selectList(any())).thenReturn(List.of(occupying));

        var slots = reservationService.availableSlots(1L, monday, monday);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).getCurrentBookings()).isEqualTo(1);
        assertThat(slots.get(0).getStatus()).isEqualTo("AVAILABLE");  // 容量2，占1
    }

    @Test
    @DisplayName("确认预约：容量校验排除自身（修复回归）")
    void confirm_excludesSelf() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            resource.setCapacity(1);
            when(reservationMapper.selectById(1L)).thenReturn(reservation);
            // 容量查询（排除自身）= 0
            when(reservationMapper.selectCount(any())).thenReturn(0L);
            when(resourceMapper.selectById(1L)).thenReturn(resource);
            when(reservationMapper.updateById(any(ResourceReservation.class))).thenReturn(1);

            reservationService.confirm(1L, "ok");
            assertThat(reservation.getStatus()).isEqualTo("RESERVED");
        }
    }
}
