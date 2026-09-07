package com.community.residence.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提交工单评价请求（工单须已完成；一单一评） */
@Data
@Schema(description = "提交工单评价请求")
public class CreateEvaluationDTO {

    @Schema(description = "评分：1-5星")
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer rating;

    @Schema(description = "评价内容")
    @Size(max = 1000, message = "评价内容最多 1000 字符")
    private String content;

    @Schema(description = "标签（逗号分隔，如 及时,专业）")
    @Size(max = 255, message = "标签最多 255 字符")
    private String tags;

    @Schema(description = "是否满意：true-满意, false-不满意")
    @NotNull(message = "是否满意不能为空")
    private Boolean isSatisfied;
}
