package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 创建房源请求（更新接口复用；同一房屋仅允许一条非下线房源） */
@Data
@Schema(description = "创建房源请求")
public class CreateHousingDTO {

    @Schema(description = "关联房屋ID")
    @NotNull(message = "关联房屋不能为空")
    private Long houseId;

    @Schema(description = "房源标题")
    @NotBlank(message = "房源标题不能为空")
    @Size(max = 200, message = "房源标题最多 200 字符")
    private String title;

    @Schema(description = "房源描述")
    @Size(max = 2000, message = "房源描述最多 2000 字符")
    private String description;

    @Schema(description = "月租金")
    @NotNull(message = "月租金不能为空")
    @DecimalMin(value = "0", message = "月租金不能为负数")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    @Schema(description = "房源图片（逗号分隔URL）")
    @Size(max = 1000, message = "图片URL总长最多 1000 字符")
    private String images;
}
