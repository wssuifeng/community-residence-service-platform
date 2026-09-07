package com.community.residence.reservation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 可预约时段响应（周循环模板按日期展开 + 占用计数） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "可预约时段")
public class AvailableSlotVO {

    @Schema(description = "时段模板ID")
    private Long timeslotId;

    @Schema(description = "日期")
    private LocalDate date;

    @Schema(description = "开始时间")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    private LocalTime endTime;

    @Schema(description = "最大可预约数（资源容量）")
    private Integer maxBookings;

    @Schema(description = "当前预约数")
    private Integer currentBookings;

    @Schema(description = "状态：AVAILABLE-可预约, FULL-已满")
    private String status;
}
