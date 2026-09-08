package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 更新全局配置请求 */
@Data
@Schema(description = "更新全局配置请求")
public class UpdateConfigDTO {

    @Schema(description = "配置值")
    @NotBlank(message = "配置值不能为空")
    private String value;
}
