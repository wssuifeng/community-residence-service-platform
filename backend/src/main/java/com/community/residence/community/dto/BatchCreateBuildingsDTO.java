package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 楼栋批量创建请求（D-端点2，部分成功语义：逐行独立不整体回滚） */
@Data
@Schema(description = "楼栋批量创建请求")
public class BatchCreateBuildingsDTO {

    @Schema(description = "所属社区ID")
    @jakarta.validation.constraints.NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "楼栋列表（上限 100）")
    @NotEmpty(message = "楼栋列表不能为空")
    @Size(max = 100, message = "单次最多创建 100 个楼栋")
    @Valid
    private List<CreateBuildingDTO> buildings;
}
