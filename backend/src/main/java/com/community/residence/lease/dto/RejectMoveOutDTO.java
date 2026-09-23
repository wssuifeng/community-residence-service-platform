package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 退租申请驳回请求（R70：理由必填，驳回后租约保持已生效） */
@Data
@Schema(description = "退租申请驳回请求")
public class RejectMoveOutDTO {

    @Schema(description = "驳回原因")
    @NotBlank(message = "驳回原因不能为空")
    @Size(max = 500, message = "驳回原因最多 500 字符")
    private String reason;
}
