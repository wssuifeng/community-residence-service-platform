package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 楼栋创建/更新请求（更新接口复用本 DTO） */
@Data
@Schema(description = "楼栋创建/更新请求")
public class CreateBuildingDTO {

    @Schema(description = "所属社区ID")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "楼栋名称")
    @NotBlank(message = "楼栋名称不能为空")
    @Size(max = 50, message = "楼栋名称最多 50 字符")
    private String name;

    @Schema(description = "楼层数")
    @NotNull(message = "楼层数不能为空")
    @Min(value = 1, message = "楼层数最少为 1")
    @Max(value = 200, message = "楼层数最多为 200")
    private Integer floors;

    @Schema(description = "楼栋描述")
    @Size(max = 500, message = "楼栋描述最多 500 字符")
    private String description;
}
