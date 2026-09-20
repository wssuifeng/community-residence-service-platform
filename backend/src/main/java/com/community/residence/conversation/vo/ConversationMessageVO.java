package com.community.residence.conversation.vo;

import com.community.residence.conversation.entity.ConversationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话消息响应（R63 契约字段：id/conversationId/senderId/senderName/senderRole/content/createdAt） */
@Data
@Schema(description = "会话消息")
public class ConversationMessageVO {

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "发送人ID")
    private Long senderId;

    @Schema(description = "发送人姓名")
    private String senderName;

    @Schema(description = "发送人角色：RESIDENT/STAFF/ADMIN/SUPER_ADMIN")
    private String senderRole;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "发送时间")
    private LocalDateTime createdAt;

    public static ConversationMessageVO from(ConversationMessage entity) {
        ConversationMessageVO vo = new ConversationMessageVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setSenderId(entity.getSenderId());
        vo.setSenderRole(entity.getSenderRole());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
