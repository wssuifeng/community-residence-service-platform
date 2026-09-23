package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 租住状态更新请求（状态机合法流转见架构设计 §6.2 六态；
 * TO_SIGN 入列见 OCR DEF-108——新状态此前被参数校验层拒收，只能经审批流进入）。
 */
@Data
@Schema(description = "租住状态更新请求")
public class UpdateLeaseStatusDTO {

    @Schema(description = "状态：PENDING-待审核, TO_SIGN-待签约, ACTIVE-租住中, MOVED_OUT-已迁出, ARCHIVED-已归档, REJECTED-已拒绝")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(PENDING|TO_SIGN|ACTIVE|MOVED_OUT|ARCHIVED|REJECTED)$", message = "租住状态不合法")
    private String status;

    @Schema(description = "备注")
    @Size(max = 200, message = "备注最多 200 字符")
    private String remark;
}
