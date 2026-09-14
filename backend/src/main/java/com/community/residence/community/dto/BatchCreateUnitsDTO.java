package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 单元批量创建请求（D-端点2，部分成功语义：逐行独立不整体回滚）。
 * DEF-032：names 元素不做 Bean Validation 级联——空串/超长由服务层
 * 逐行校验反馈（元素注解会在 Controller 层整批 400）。
 */
@Data
@Schema(description = "单元批量创建请求")
public class BatchCreateUnitsDTO {

    @Schema(description = "所属楼栋ID")
    @NotNull(message = "所属楼栋不能为空")
    private Long buildingId;

    @Schema(description = "单元名称列表（逐项插入，空串/超长项逐行反馈）")
    @NotEmpty(message = "单元名称列表不能为空")
    @Size(max = 100, message = "单次最多创建 100 个单元")
    private List<String> names;
}
