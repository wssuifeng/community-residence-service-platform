package com.community.residence.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 不满意评价跟进请求 */
@Data
@Schema(description = "不满意评价跟进请求")
public class CreateFollowUpDTO {

    @Schema(description = "跟进内容")
    @NotBlank(message = "跟进内容不能为空")
    @Size(max = 1000, message = "跟进内容最多 1000 字符")
    private String content;
}
