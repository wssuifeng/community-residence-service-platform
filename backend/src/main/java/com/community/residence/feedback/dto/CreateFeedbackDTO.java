package com.community.residence.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提交反馈请求（社区归属由居民在住关系推导，无在住关系时拒绝） */
@Data
@Schema(description = "提交反馈请求")
public class CreateFeedbackDTO {

    @Schema(description = "所属社区ID")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "反馈标题")
    @NotBlank(message = "反馈标题不能为空")
    @Size(max = 100, message = "反馈标题最多 100 字符")
    private String title;

    @Schema(description = "反馈内容")
    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 2000, message = "反馈内容最多 2000 字符")
    private String content;

    @Schema(description = "反馈类别：SUGGESTION-建议, COMPLAINT-投诉, INQUIRY-咨询")
    @NotBlank(message = "反馈类别不能为空")
    @Pattern(regexp = "^(SUGGESTION|COMPLAINT|INQUIRY)$", message = "反馈类别不合法")
    private String category;
}
