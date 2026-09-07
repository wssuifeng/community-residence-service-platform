package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 社区创建/更新请求（更新接口复用本 DTO） */
@Data
@Schema(description = "社区创建/更新请求")
public class CreateCommunityDTO {

    @Schema(description = "社区名称")
    @NotBlank(message = "社区名称不能为空")
    @Size(max = 100, message = "社区名称最多 100 字符")
    private String name;

    @Schema(description = "详细地址")
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址最多 255 字符")
    private String address;

    @Schema(description = "联系电话（座机或手机号）")
    @Pattern(regexp = "^\\d{3,4}-\\d{7,8}$|^1[3-9]\\d{9}$", message = "联系电话格式不正确")
    private String contactPhone;

    @Schema(description = "联系人")
    @Size(max = 50, message = "联系人最多 50 字符")
    private String contactPerson;

    @Schema(description = "社区简介")
    @Size(max = 1000, message = "社区简介最多 1000 字符")
    private String description;
}
