package com.community.residence.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公告撤回请求 */
@Data
@Schema(description = "公告撤回请求")
public class WithdrawNoticeDTO {

    @Schema(description = "撤回原因")
    @NotBlank(message = "撤回原因不能为空")
    @Size(max = 200, message = "撤回原因最多 200 字符")
    private String reason;
}
