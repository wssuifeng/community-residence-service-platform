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

/** 看房可约时段测试（BE-ISSUE-5 + DEF-061）：周模板展开、60 分钟栅格切片、
 * 占用重叠计数、状态标注 */
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

    /** 栅格标识：模板ID×10000+当日分钟数（SlotGrids.gridId，C7 同口径） */
    private long gridId(long templateId, LocalTime start) {
        return templateId * 10000 + start.getHour() * 60L + start.getMinute();
    }

    private ViewingAppointment booked(LocalDate date, String start, String end, String status) {
        ViewingAppointment a = new ViewingAppointment();
        a.setHousingId(1L);
        a.setAppointmentDate(date);
        a.setStartTime(LocalTime.parse(start));
        a.setEndTime(LocalTime.parse(end));
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("命中 dayOfWeek 模板：无预约 → AVAILABLE，maxBookings=1（DEF-061 栅格标识）")
    void availableSlots_expandsTemplateForDate() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTimeslotId()).isEqualTo(gridId(3L, LocalTime.of(10, 0)));
        assertThat(result.get(0).getMaxBookings()).isEqualTo(1);
        assertThat(result.get(0).getCurrentBookings()).isEqualTo(0);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("占用计数：切片与 TO_CONFIRM 预约重叠 → FULL")
    void availableSlots_occupiedMarkedFull() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(3L, 1, "10:00", "11:00")));
        when(appointmentMapper.selectList(any()))
                .thenReturn(List.of(booked(monday, "10:00", "11:00", "TO_CONFIRM")));

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
                .thenReturn(List.of(booked(monday, "10:00", "11:00", "CANCELLED")));

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
        assertThat(result.get(0).getTimeslotId()).isEqualTo(gridId(3L, LocalTime.of(10, 0)));
        assertThat(result.get(1).getDate()).isEqualTo(monday.plusDays(2));
        assertThat(result.get(1).getTimeslotId()).isEqualTo(gridId(4L, LocalTime.of(14, 0)));
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

    /* ---- DEF-061：60 分钟栅格切片 ---- */

    @Test
    @DisplayName("3 小时模板段（V6 种子 09:00-12:00）切 3 个 60 分钟切片，栅格标识=模板ID×10000+分钟")
    void availableSlots_threeHourTemplate_slicedInto3() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(5L, 1, "09:00", "12:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(result.get(0).getEndTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(result.get(1).getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(result.get(1).getEndTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(result.get(2).getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(result.get(2).getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(result.get(0).getTimeslotId()).isEqualTo(gridId(5L, LocalTime.of(9, 0)));
        assertThat(result.get(1).getTimeslotId()).isEqualTo(gridId(5L, LocalTime.of(10, 0)));
        assertThat(result.get(2).getTimeslotId()).isEqualTo(gridId(5L, LocalTime.of(11, 0)));
        assertThat(result).allSatisfy(s -> {
            assertThat(s.getMaxBookings()).isEqualTo(1);
            assertThat(s.getStatus()).isEqualTo("AVAILABLE");
        });
    }

    @Test
    @DisplayName("尾段不足 60 分钟丢弃：14:00-16:30 只产出 14:00/15:00 两片")
    void availableSlots_tailShorterThan60_dropped() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(6L, 1, "14:00", "16:30")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(result.get(0).getEndTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(result.get(1).getStartTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(result.get(1).getEndTime()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    @DisplayName("模板不足 60 分钟整段不产出切片")
    void availableSlots_templateShorterThan60_noSlice() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(6L, 1, "14:00", "14:30")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        assertThat(housingService.availableSlots(1L, monday, monday)).isEmpty();
    }

    @Test
    @DisplayName("切片占用重叠计数：一条跨两片的预约（10:00-12:00）使两片均 FULL")
    void availableSlots_crossSliceBooking_fillsBoth() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(5L, 1, "09:00", "12:00")));
        when(appointmentMapper.selectList(any()))
                .thenReturn(List.of(booked(monday, "10:00", "12:00", "TO_CONFIRM")));

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        /* 09:00-10:00 片不重叠仍可约；10:00-11:00 与 11:00-12:00 两片被跨片预约占满 */
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
        assertThat(result.get(0).getCurrentBookings()).isEqualTo(0);
        assertThat(result.get(1).getStatus()).isEqualTo("FULL");
        assertThat(result.get(1).getCurrentBookings()).isEqualTo(1);
        assertThat(result.get(2).getStatus()).isEqualTo("FULL");
        assertThat(result.get(2).getCurrentBookings()).isEqualTo(1);
    }

    @Test
    @DisplayName("半开区间口径：预约与切片首尾相接（预约 09:00-10:00 vs 切片 10:00-11:00）不计数")
    void availableSlots_adjacentBooking_notCounted() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(5L, 1, "09:00", "12:00")));
        when(appointmentMapper.selectList(any()))
                .thenReturn(List.of(booked(monday, "09:00", "10:00", "RESERVED")));

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result.get(0).getStatus()).isEqualTo("FULL");
        assertThat(result.get(1).getStatus()).isEqualTo("AVAILABLE");
        assertThat(result.get(1).getCurrentBookings()).isEqualTo(0);
    }

    @Test
    @DisplayName("同日多模板：V6 双段（09:00-12:00 + 14:00-18:00）切 3+4 片并按 startTime 排序")
    void availableSlots_twoTemplates_sortedByStart() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(8L, 1, "14:00", "18:00"), slot(7L, 1, "09:00", "12:00")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        assertThat(result).hasSize(7);
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getStartTime())
                    .isAfterOrEqualTo(result.get(i - 1).getStartTime());
        }
        assertThat(result.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        /* 第 4 片起为下午段（09/10/11 + 14/15/16/17） */
        assertThat(result.get(3).getStartTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(result.get(6).getStartTime()).isEqualTo(LocalTime.of(17, 0));
    }

    /* ---- DEF-062：切片步进跨 24:00 不得死循环 ---- */

    @Test
    @DisplayName("深夜模板（22:00-23:30）切片步进不跨 24:00 绕回：只产出 22:00-23:00 一片")
    void availableSlots_lateNightTemplate_noMidnightWrapLoop() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(9L, 1, "22:00", "23:30")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, monday, monday);

        /* 旧实现以「起点 +60 分钟 isAfter 模板终点」为终止条件，23:00+60 绕回 00:00
           后条件永不为真 → 无限追加切片直至 OOM（2026-09-20 实测） */
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStartTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(result.get(0).getEndTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(result.get(0).getTimeslotId()).isEqualTo(gridId(9L, LocalTime.of(22, 0)));
    }

    @Test
    @DisplayName("模板终点不足 60 分钟落到次日：23:30-23:59 整段不产出切片（不绕回死循环）")
    void availableSlots_templateEndBeyondMidnight_noSlice() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        when(housingMapper.selectById(1L)).thenReturn(new Housing());
        when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(9L, 1, "23:30", "23:59")));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        assertThat(housingService.availableSlots(1L, monday, monday)).isEmpty();
    }
}
