package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 房屋批量创建请求（D-端点2，部分成功语义：逐行独立不整体回滚） */
@Data
@Schema(description = "房屋批量创建请求")
public class BatchCreateHousesDTO {

    @Schema(description = "所属单元ID")
    @NotNull(message = "所属单元不能为空")
    private Long unitId;

    @Schema(description = "房屋列表（上限 200，前端批量生成上限对齐）")
    @NotEmpty(message = "房屋列表不能为空")
    @Size(max = 200, message = "单次最多创建 200 套房屋")
    @Valid
    private List<CreateHouseDTO> houses;
}
