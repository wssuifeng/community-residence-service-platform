package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 房屋批量创建请求（D-端点2，部分成功语义：逐行独立不整体回滚）。
 * DEF-032：houses 元素不做 @Valid 级联——字段校验（@NotNull 等）由服务层
 * 逐行 Validator 执行，坏行逐行反馈原因而非整批 400。
 */
@Data
@Schema(description = "房屋批量创建请求")
public class BatchCreateHousesDTO {

    @Schema(description = "所属单元ID")
    @NotNull(message = "所属单元不能为空")
    private Long unitId;

    @Schema(description = "房屋列表（上限 200，字段校验逐行执行）")
    @NotEmpty(message = "房屋列表不能为空")
    @Size(max = 200, message = "单次最多创建 200 套房屋")
    private List<CreateHouseDTO> houses;
}
