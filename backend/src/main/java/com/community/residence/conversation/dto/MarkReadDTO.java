package com.community.residence.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 标记会话已读请求（R63：更新参与者已读游标） */
@Data
@Schema(description = "标记会话已读请求")
public class MarkReadDTO {

    @Schema(description = "最后已读消息ID")
    @NotNull(message = "lastMessageId 不能为空")
    private Long lastMessageId;
}
