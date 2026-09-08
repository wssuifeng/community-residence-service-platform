package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 租住状态更新请求（PENDING→ACTIVE/REJECTED；ACTIVE→MOVED_OUT/ARCHIVED） */
@Data
@Schema(description = "租住状态更新请求")
public class UpdateLeaseStatusDTO {

    @Schema(description = "状态：PENDING-待审核, ACTIVE-租住中, MOVED_OUT-已迁出, ARCHIVED-已归档, REJECTED-已拒绝")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(PENDING|ACTIVE|MOVED_OUT|ARCHIVED|REJECTED)$", message = "租住状态不合法")
    private String status;

    @Schema(description = "备注")
    @Size(max = 200, message = "备注最多 200 字符")
    private String remark;
}
