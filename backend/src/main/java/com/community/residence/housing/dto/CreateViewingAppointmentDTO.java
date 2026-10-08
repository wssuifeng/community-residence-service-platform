package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建看房预约请求：仅注册并登录的居民账号可提交，预约人身份从 JWT 取得。
 * `visitorName` 仅为历史请求兼容字段，新接口不再消费游客身份信息。
 */
@Data
@Schema(description = "创建看房预约请求")
public class CreateViewingAppointmentDTO {

    @Schema(description = "房源ID（会话内发起 R69 可省略，服务端按租约房屋推导）")
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

    @Schema(description = "历史兼容字段（新预约不使用）", hidden = true, deprecated = true)
    @Size(max = 50, message = "姓名最多 50 字符")
    private String visitorName;

    @Schema(description = "联系电话（可选；为空时服务端取居民档案手机号）")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String contactPhone;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
