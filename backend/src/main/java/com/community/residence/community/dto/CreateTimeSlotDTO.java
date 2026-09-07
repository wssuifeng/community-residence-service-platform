package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

/** 资源时段创建请求（资源ID 取路径参数；按周循环模板） */
@Data
@Schema(description = "资源时段创建请求")
public class CreateTimeSlotDTO {

    @Schema(description = "星期几：1-周一, 7-周日")
    @NotNull(message = "星期几不能为空")
    @Min(value = 1, message = "星期几取值 1~7")
    @Max(value = 7, message = "星期几取值 1~7")
    private Integer dayOfWeek;

    @Schema(description = "开始时间")
    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    @Schema(description = "是否可预约：0-不可预约, 1-可预约")
    @Min(value = 0, message = "是否可预约取值 0/1")
    @Max(value = 1, message = "是否可预约取值 0/1")
    private Integer isAvailable;
}
