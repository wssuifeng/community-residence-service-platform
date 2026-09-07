package com.community.residence.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 预约处置请求（确认/拒绝/取消/违约/完成共用，动作语义由端点决定） */
@Data
@Schema(description = "预约处置请求")
public class ReservationActionDTO {

    @Schema(description = "处置原因/备注")
    @NotBlank(message = "原因不能为空")
    @Size(max = 200, message = "原因最多 200 字符")
    private String reason;
}
