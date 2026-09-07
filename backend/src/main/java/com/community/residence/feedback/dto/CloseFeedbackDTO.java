package com.community.residence.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 反馈办结请求 */
@Data
@Schema(description = "反馈办结请求")
public class CloseFeedbackDTO {

    @Schema(description = "办结说明")
    @NotBlank(message = "办结说明不能为空")
    @Size(max = 200, message = "办结说明最多 200 字符")
    private String remark;
}
