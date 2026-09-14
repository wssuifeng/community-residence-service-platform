package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 单元批量创建请求（D-端点2，部分成功语义：逐行独立不整体回滚） */
@Data
@Schema(description = "单元批量创建请求")
public class BatchCreateUnitsDTO {

    @Schema(description = "所属楼栋ID")
    @NotNull(message = "所属楼栋不能为空")
    private Long buildingId;

    @Schema(description = "单元名称列表（逐项插入，空串/超长项逐行失败）")
    @NotEmpty(message = "单元名称列表不能为空")
    @Size(max = 100, message = "单次最多创建 100 个单元")
    private List<@NotBlank(message = "单元名称不能为空")
                @Size(max = 50, message = "单元名称最多 50 字符") String> names;
}
