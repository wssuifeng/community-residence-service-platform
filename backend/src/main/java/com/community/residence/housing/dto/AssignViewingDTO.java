package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 分配带看人请求（R59）：目标为启用状态的 STAFF/ADMIN 账号，重复分配覆盖 */
@Data
@Schema(description = "分配带看人请求")
public class AssignViewingDTO {

    @Schema(description = "带看人账号ID（sys_user）")
    @NotNull(message = "带看人不能为空")
    private Long assigneeId;
}
