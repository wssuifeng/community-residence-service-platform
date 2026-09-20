package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 社区批量操作请求（批量删除）：ids 非空、单次上限 50 */
@Data
@Schema(description = "社区批量删除请求")
public class BatchCommunityIdsDTO {

    @Schema(description = "社区ID列表（非空，单次上限 50；重复 ID 自动去重）")
    @NotEmpty(message = "请至少选择一个社区")
    @Size(max = 50, message = "单次最多处理 50 个社区")
    private List<Long> ids;
}
