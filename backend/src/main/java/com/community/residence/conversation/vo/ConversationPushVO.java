package com.community.residence.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 会话 WebSocket 推送载荷（R63，type + data 包裹，口径同 R30 反馈会话/R59 看房会话） */
@Data
@Schema(description = "会话 WebSocket 推送载荷")
public class ConversationPushVO {

    @Schema(description = "消息类型：CONVERSATION_MESSAGE-会话消息")
    private String type;

    @Schema(description = "载荷内容：CONVERSATION_MESSAGE 为会话消息")
    private Object data;

    public static ConversationPushVO message(ConversationMessageVO data) {
        ConversationPushVO vo = new ConversationPushVO();
        vo.setType("CONVERSATION_MESSAGE");
        vo.setData(data);
        return vo;
    }
}
