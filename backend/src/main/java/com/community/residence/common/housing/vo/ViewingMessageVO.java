package com.community.residence.common.housing.vo;

import com.community.residence.common.housing.entity.ViewingMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 看房会话消息响应（R59；契约字段：id/appointmentId/senderId/senderName/content/createdAt） */
@Data
@Schema(description = "看房会话消息")
public class ViewingMessageVO {

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "看房预约ID")
    private Long appointmentId;

    @Schema(description = "发送人ID")
    private Long senderId;

    @Schema(description = "发送人姓名")
    private String senderName;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description="消息类型TEXT/IMAGE")
    private String messageType;


    @Schema(description="图片消息内容，仅IMAGE时存在")
    private com.community.residence.common.conversation.vo.ChatImageVO image;


    @Schema(description = "发送时间")
    private LocalDateTime createdAt;

    public static ViewingMessageVO from(ViewingMessage entity) {
        ViewingMessageVO vo = new ViewingMessageVO();
        vo.setId(entity.getId());
        vo.setAppointmentId(entity.getAppointmentId());
        vo.setSenderId(entity.getSenderId());
        vo.setContent(entity.getContent());
        vo.setMessageType(entity.getMessageType()==null ? com.community.residence.common.conversation.constant.ChatMessageType.TEXT : entity.getMessageType());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
