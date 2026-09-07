package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 社区状态更新请求 */
@Data
@Schema(description = "社区状态更新请求")
public class UpdateCommunityStatusDTO {

    @Schema(description = "状态：ACTIVE-运营中, INACTIVE-已停用")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "状态仅支持 ACTIVE/INACTIVE")
    private String status;
}
