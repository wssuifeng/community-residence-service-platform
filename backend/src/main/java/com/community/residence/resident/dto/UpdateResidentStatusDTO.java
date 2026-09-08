package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 居民账号冻结/解冻请求（管理员操作） */
@Data
@Schema(description = "居民账号冻结/解冻请求")
public class UpdateResidentStatusDTO {

    @Schema(description = "状态：ACTIVE-正常, FROZEN-冻结")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(ACTIVE|FROZEN)$", message = "状态仅支持 ACTIVE/FROZEN")
    private String status;

    @Schema(description = "操作原因")
    @Size(max = 200, message = "原因最多 200 字符")
    private String reason;
}
