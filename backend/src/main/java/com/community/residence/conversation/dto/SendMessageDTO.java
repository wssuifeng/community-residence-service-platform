package com.community.residence.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发送会话消息请求（R63；上限对齐 conversation_message.content VARCHAR(500)） */
@Data
@Schema(description = "发送会话消息请求")
public class SendMessageDTO {

    @Schema(description = "消息内容")
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息内容最多 500 字符")
    private String content;
}
