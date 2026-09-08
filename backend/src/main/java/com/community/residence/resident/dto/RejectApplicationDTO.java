package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 入住申请审批拒绝请求 */
@Data
@Schema(description = "入住申请审批拒绝请求")
public class RejectApplicationDTO {

    @Schema(description = "拒绝原因")
    @NotBlank(message = "拒绝原因不能为空")
    @Size(max = 500, message = "拒绝原因最多 500 字符")
    private String reason;
}
