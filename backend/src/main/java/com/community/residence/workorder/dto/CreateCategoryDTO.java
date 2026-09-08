package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建/更新服务类别请求（parentId 为空是一级，非空是二级） */
@Data
@Schema(description = "创建/更新服务类别请求")
public class CreateCategoryDTO {

    @Schema(description = "所属社区ID")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "类别名称")
    @NotBlank(message = "类别名称不能为空")
    @Size(max = 50, message = "类别名称最多 50 字符")
    private String name;

    @Schema(description = "类别描述")
    @Size(max = 500, message = "类别描述最多 500 字符")
    private String description;

    @Schema(description = "父类别ID（二级分类时填写）")
    private Long parentId;

    @Schema(description = "排序（小值在前）")
    private Integer sortOrder;

    @Schema(description = "是否启用：0-禁用, 1-启用")
    private Integer isActive;
}
