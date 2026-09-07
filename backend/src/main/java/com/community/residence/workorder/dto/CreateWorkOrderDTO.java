package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提交工单请求（居民端；社区归属由服务类别推导） */
@Data
@Schema(description = "提交工单请求")
public class CreateWorkOrderDTO {

    @Schema(description = "服务类别ID")
    @NotNull(message = "服务类别不能为空")
    private Long categoryId;

    @Schema(description = "工单标题")
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 100, message = "工单标题最多 100 字符")
    private String title;

    @Schema(description = "工单内容")
    @NotBlank(message = "工单内容不能为空")
    @Size(max = 2000, message = "工单内容最多 2000 字符")
    private String content;

    @Schema(description = "联系电话")
    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String contactPhone;

    @Schema(description = "服务地址")
    @Size(max = 255, message = "服务地址最多 255 字符")
    private String address;

    @Schema(description = "优先级：LOW-低, NORMAL-普通, HIGH-高, URGENT-紧急")
    @Pattern(regexp = "^(LOW|NORMAL|HIGH|URGENT)$", message = "优先级不合法")
    private String priority;
}
