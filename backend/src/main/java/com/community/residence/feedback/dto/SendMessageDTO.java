package com.community.residence.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发送反馈会话消息请求 */
@Data
@Schema(description = "发送反馈会话消息请求")
public class SendMessageDTO {

    @Schema(description = "消息内容")
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "消息内容最多 2000 字符")
    private String content;

    @Schema(description = "父消息ID（回复引用，可选）")
    private Long parentId;
}
