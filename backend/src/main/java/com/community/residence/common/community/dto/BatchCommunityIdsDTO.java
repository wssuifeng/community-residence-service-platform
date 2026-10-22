package com.community.residence.common.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 小区批量操作请求（批量删除）：ids 非空、单次上限 50 */
@Data
@Schema(description = "小区批量删除请求")
public class BatchCommunityIdsDTO {

    @Schema(description = "小区ID列表（非空，单次上限 50；重复 ID 自动去重）")
    @NotEmpty(message = "请至少选择一个小区")
    @Size(max = 50, message = "单次最多处理 50 个小区")
    private List<Long> ids;
    @Schema(description="逐小区删除影响确认版本；缺失或过期的条目拒绝删除")
    private java.util.Map<Long, String> confirmationVersions;
    @Schema(description="逐小区完整名称确认；缺失或不匹配拒绝删除")
    private java.util.Map<Long, String> confirmationNames;
}
