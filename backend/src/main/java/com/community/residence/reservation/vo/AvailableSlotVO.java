package com.community.residence.reservation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 可预约时段响应（Slot Grid 栅格化：资源 slot_unit 粒度标准化栅格 + 占用分桶计数） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "可预约时段")
public class AvailableSlotVO {

    @Schema(description = "栅格标识（模板ID×10000+当日分钟数，前端选择键，非数据库主键）")
    private Long timeslotId;

    @Schema(description = "日期")
    private LocalDate date;

    @Schema(description = "开始时间")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    private LocalTime endTime;

    @Schema(description = "每时段可承载人数上限（资源级容量，Slot Grid 口径）")
    private Integer maxBookings;

    @Schema(description = "当前占用数（覆盖即计入）")
    private Integer currentBookings;

    @Schema(description = "状态：AVAILABLE-可预约, FULL-已满")
    private String status;
}
