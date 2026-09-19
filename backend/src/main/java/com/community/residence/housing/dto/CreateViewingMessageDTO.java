package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发送看房会话消息请求（R59） */
@Data
@Schema(description = "发送看房会话消息请求")
public class CreateViewingMessageDTO {

    @Schema(description = "消息内容")
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息内容最多 500 字符")
    private String content;
}
