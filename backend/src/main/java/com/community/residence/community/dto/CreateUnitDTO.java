package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 单元创建/更新请求（更新接口复用本 DTO；社区ID 由楼栋推导，不由客户端传入） */
@Data
@Schema(description = "单元创建/更新请求")
public class CreateUnitDTO {

    @Schema(description = "所属楼栋ID")
    @NotNull(message = "所属楼栋不能为空")
    private Long buildingId;

    @Schema(description = "单元名称")
    @NotBlank(message = "单元名称不能为空")
    @Size(max = 50, message = "单元名称最多 50 字符")
    private String name;

    @Schema(description = "单元描述")
    @Size(max = 500, message = "单元描述最多 500 字符")
    private String description;
}
