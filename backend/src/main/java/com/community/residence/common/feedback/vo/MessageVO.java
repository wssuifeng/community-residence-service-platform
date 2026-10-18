package com.community.residence.common.feedback.vo;

import com.community.residence.common.feedback.entity.FeedbackMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 反馈会话消息响应 */
@Data
@Schema(description = "反馈会话消息")
public class MessageVO {

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "反馈ID")
    private Long feedbackId;

    @Schema(description = "发送人ID")
    private Long senderId;

    @Schema(description = "发送人姓名")
    private String senderName;

    @Schema(description = "发送人类型：RESIDENT-居民, ADMIN-管理员")
    private String senderType;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description="消息类型TEXT/IMAGE")
    private String messageType;


    @Schema(description="图片消息内容，仅IMAGE时存在")
    private com.community.residence.common.conversation.vo.ChatImageVO image;


    @Schema(description = "父消息ID")
    private Long parentId;

    @Schema(description = "发送时间")
    private LocalDateTime createdAt;

    public static MessageVO from(FeedbackMessage entity) {
        MessageVO vo = new MessageVO();
        vo.setId(entity.getId());
        vo.setFeedbackId(entity.getFeedbackId());
        vo.setSenderId(entity.getSenderId());
        vo.setSenderType(entity.getSenderType());
        vo.setContent(entity.getContent());
        vo.setMessageType(entity.getMessageType()==null ? com.community.residence.common.conversation.constant.ChatMessageType.TEXT : entity.getMessageType());
        vo.setParentId(entity.getParentId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
