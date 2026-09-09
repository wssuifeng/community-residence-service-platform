package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.reservation.vo.AvailableSlotVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 看房可约时段测试（BE-ISSUE-5）：周模板展开、占用计数、状态标注 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HousingService 可约时段单元测试")
class HousingAvailableSlotsTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ViewingAppointmentMapper appointmentMapper;

    @InjectMocks
    private HousingService housingService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Housing.class);
        TableInfoHelper.initTableInfo(assistant, HousingTimeslot.class);
        TableInfoHelper.initTableInfo(assistant, ViewingAppointment.class);
    }

    private HousingTimeslot slot(long id, int dayOfWeek, String start, String end) {
        HousingTimeslot s = new HousingTimeslot();
        s.setId(id);
        s.setHousingId(1L);
        s.setDayOfWeek(dayOfWeek);
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        s.setIsAvailable(1);
        return s;
    }

    private ViewingAppointment booked(LocalDate date, String start, String status) {
        ViewingAppointment a = new ViewingAppointment();
        a.setHousingId(1L);
        a.setAppointmentDate(date);
        a.setStartTime(LocalTime.parse(start));
        a.setEndTime(LocalTime.parse(start).plusHours(1));
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("命中 dayOfWeek 模板：无预约 → AVAILABLE，maxBookings=1")
    void availableSlots_expandsTemplateForDate() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTimeslotId()).isEqualTo(3L);
        assertThat(result.get(0).getMaxBookings()).isEqualTo(1);
        assertThat(result.get(0).getCurrentBookings()).isEqualTo(0);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("占用计数：时段已有 TO_CONFIRM 预约 → FULL")
    void availableSlots_occupiedMarkedFull() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any()))
                .thenReturn(List.of(booked(monday, "10:00", "TO_CONFIRM")));

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCurrentBookings()).isEqualTo(1);
        assertThat(result.get(0).getStatus()).isEqualTo("FULL");
    }

    @Test
    @DisplayName("状态过滤：CANCELLED 预约不占容量")
    void availableSlots_cancelledNotCounted() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any()))
                .thenReturn(List.of(booked(monday, "10:00", "CANCELLED")));

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result.get(0).getCurrentBookings()).isEqualTo(0);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("日期范围：跨 3 天展开两个不同 dayOfWeek 模板，按日期升序")
    void availableSlots_expandsAcrossDateRange() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(4L, 3, "14:00", "15:00"), slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday.plusDays(2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDate()).isEqualTo(monday);
        assertThat(result.get(0).getTimeslotId()).isEqualTo(3L);
        assertThat(result.get(1).getDate()).isEqualTo(monday.plusDays(2));
        assertThat(result.get(1).getTimeslotId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("指定星期无模板：返回空数组")
    void availableSlots_noTemplateForDay_empty() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(4L, 3, "14:00", "15:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        assertThat(housingService.availableSlots(1L, monday, monday)).isEmpty();
    }
}
