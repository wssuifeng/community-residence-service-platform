package com.community.residence.common.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 小区批量状态变更请求（停用/启用）：ids 非空、单次上限 50，status 仅 ACTIVE/INACTIVE */
@Data
@Schema(description = "小区批量状态变更请求")
public class BatchCommunityStatusDTO {

    @Schema(description = "小区ID列表（非空，单次上限 50；重复 ID 自动去重）")
    @NotEmpty(message = "请至少选择一个小区")
    @Size(max = 50, message = "单次最多处理 50 个小区")
    private List<Long> ids;

    @Schema(description = "状态：ACTIVE-运营中, INACTIVE-已停用")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "状态仅支持 ACTIVE/INACTIVE")
    private String status;
}
