package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 房屋创建/更新请求（更新接口复用本 DTO；社区ID 由单元推导） */
@Data
@Schema(description = "房屋创建/更新请求")
public class CreateHouseDTO {

    @Schema(description = "所属单元ID")
    @NotNull(message = "所属单元不能为空")
    private Long unitId;

    @Schema(description = "房号")
    @NotBlank(message = "房号不能为空")
    @Size(max = 20, message = "房号最多 20 字符")
    private String houseNumber;

    @Schema(description = "楼层")
    @NotNull(message = "楼层不能为空")
    @Min(value = 1, message = "楼层最少为 1")
    private Integer floor;

    @Schema(description = "建筑面积（平方米）")
    @NotNull(message = "建筑面积不能为空")
    @DecimalMin(value = "0", message = "建筑面积不能为负数")
    private BigDecimal area;

    @Schema(description = "房间数")
    @Min(value = 0, message = "房间数不能为负数")
    private Integer roomCount;

    @Schema(description = "户型（如 2室1厅1卫）")
    @Size(max = 50, message = "户型最多 50 字符")
    private String layout;

    @Schema(description = "朝向")
    @Size(max = 20, message = "朝向最多 20 字符")
    private String orientation;

    @Schema(description = "状态：VACANT-空置, OCCUPIED-已入住, RESERVED-预留, MAINTENANCE-维护中")
    @Pattern(regexp = "^(VACANT|OCCUPIED|RESERVED|MAINTENANCE)$", message = "房屋状态不合法")
    private String status;

    @Schema(description = "房屋描述")
    @Size(max = 1000, message = "房屋描述最多 1000 字符")
    private String description;
}
