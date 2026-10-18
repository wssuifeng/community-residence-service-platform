package com.community.residence.common.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Schema(description = "业务编号；创建留空按本级结构分配，更新留空保留")
    @Size(max=32)
    private String businessCode;

    @Schema(description = "详细地址")
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址最多 255 字符")
    private String address;

    @Schema(description = "社区责任人账号ID，必须为ADMIN；null表示未设置")
    @jakarta.validation.constraints.Positive
    private Long responsibleAdminId;

    @Schema(description = "社区简介")
    @Size(max = 1000, message = "社区简介最多 1000 字符")
    private String description;

    @Schema(description = "入住申请自动通过：0-关闭（人工审核）, 1-开启（提交即通过）；缺省视为 0")
    @Min(value = 0, message = "自动通过开关取值不合法")
    @Max(value = 1, message = "自动通过开关取值不合法")
    private Integer autoApproveResidence;

    @Schema(description = "自动通过时的默认租期月数（1~120）；缺省视为 12")
    @Min(value = 1, message = "默认租期至少 1 个月")
    @Max(value = 120, message = "默认租期最多 120 个月")
    private Integer defaultLeaseMonths;
}
