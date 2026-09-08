package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 派单请求（管理员指派服务人员） */
@Data
@Schema(description = "派单请求")
public class AssignWorkOrderDTO {

    @Schema(description = "服务人员ID（sys_user STAFF 账号ID，字段名为 assigneeId 非 assignedStaffId）")
    @NotNull(message = "服务人员不能为空")
    private Long assigneeId;

    @Schema(description = "派单备注")
    @Size(max = 200, message = "备注最多 200 字符")
    private String remark;
}
