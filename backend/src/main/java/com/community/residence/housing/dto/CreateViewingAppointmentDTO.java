package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建看房预约请求：居民登录预约带空 visitor 字段（取令牌身份）；
 * 游客预约必填 visitorName/visitorPhone（user_id 留空）。
 */
@Data
@Schema(description = "创建看房预约请求")
public class CreateViewingAppointmentDTO {

    @Schema(description = "房源ID")
    @NotNull(message = "房源不能为空")
    private Long housingId;

    @Schema(description = "预约日期")
    @NotNull(message = "预约日期不能为空")
    private LocalDate appointmentDate;

    @Schema(description = "开始时间（须落在房源可预约时段内）")
    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    @Schema(description = "游客姓名（游客预约必填，居民预约忽略）")
    @Size(max = 50, message = "游客姓名最多 50 字符")
    private String visitorName;

    @Schema(description = "联系电话")
    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String contactPhone;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
