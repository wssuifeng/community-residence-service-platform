package com.community.residence.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 创建资源预约请求（时段取资源周循环模板内的时间段） */
@Data
@Schema(description = "创建资源预约请求")
public class CreateReservationDTO {

    @Schema(description = "资源ID")
    @NotNull(message = "资源不能为空")
    private Long resourceId;

    @Schema(description = "预约日期")
    @NotNull(message = "预约日期不能为空")
    private LocalDate reserveDate;

    @Schema(description = "开始时间（须与资源时段模板一致）")
    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @Schema(description = "结束时间（须与资源时段模板一致）")
    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    @Schema(description = "预约用途")
    @Size(max = 200, message = "预约用途最多 200 字符")
    private String purpose;

    @Schema(description = "联系电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String contactPhone;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
