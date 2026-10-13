package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** 明确选区、明确修改字段，不将空白表单误写为覆盖。 */
@Data
@Schema(description = "房屋自身属性批量编辑，不改变结构归属、状态或居住关系")
public class BatchHouseAttributesDTO {
    @Schema(description = "所选房屋ID，最多1000套")
    @NotEmpty @Size(max=1000)
    private List<@NotNull @Positive Long> houseIds;
    @Schema(description = "明确修改字段：houseNumber/floor/area/layout/orientation/description")
    @NotEmpty
    private Set<String> fields;
    @Schema(description = "房号，仅单选可修改")
    @Size(max=20)
    private String houseNumber;
    @Schema(description = "各房屋所属楼栋内的楼层")
    @Min(1)
    private Integer floor;
    @Schema(description = "面积，平方米")
    @DecimalMin("0")
    private BigDecimal area;
    @Schema(description = "户型，选中字段后空白表示清除")
    @Size(max=50)
    private String layout;
    @Schema(description = "朝向，选中字段后空白表示清除")
    @Size(max=20)
    private String orientation;
    @Schema(description = "描述，选中字段后空白表示清除")
    @Size(max=1000)
    private String description;
}
