package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提交入住申请请求（社区与房屋归属由 houseId 推导） */
@Data
@Schema(description = "提交入住申请请求")
public class CreateApplicationDTO {

    @Schema(description = "申请房屋ID")
    @NotNull(message = "申请房屋不能为空")
    private Long houseId;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    @NotBlank(message = "关系类型不能为空")
    @Pattern(regexp = "^(OWNER|TENANT|FAMILY)$", message = "关系类型不合法")
    private String relationType;

    @Schema(description = "申请说明")
    @Size(max = 500, message = "申请说明最多 500 字符")
    private String remark;
}
