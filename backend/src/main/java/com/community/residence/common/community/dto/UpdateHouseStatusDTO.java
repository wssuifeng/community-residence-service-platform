package com.community.residence.common.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 房屋状态更新请求（变更记录写入 house_status_history） */
@Data
@Schema(description = "房屋状态更新请求")
public class UpdateHouseStatusDTO {

    @Schema(description = "状态：VACANT-空置, MAINTENANCE-维护中, INACTIVE-停用（入住由关系驱动）")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(VACANT|MAINTENANCE|INACTIVE)$", message = "房屋状态不合法")
    private String status;

    @Schema(description = "变更备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
