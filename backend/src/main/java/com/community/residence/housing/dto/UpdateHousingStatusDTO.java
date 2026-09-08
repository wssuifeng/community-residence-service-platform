package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 房源状态更新请求 */
@Data
@Schema(description = "房源状态更新请求")
public class UpdateHousingStatusDTO {

    @Schema(description = "状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(AVAILABLE|RESERVED|RENTED|OFFLINE)$", message = "房源状态不合法")
    private String status;

    @Schema(description = "备注")
    @Size(max = 200, message = "备注最多 200 字符")
    private String remark;
}
