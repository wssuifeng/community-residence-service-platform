package com.community.residence.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话消息（conversation_message 表；sender_id 多态引用不设外键，R63） */
@Data
@TableName("conversation_message")
@Schema(description = "会话消息")
public class ConversationMessage {

    @TableId(type = IdType.AUTO)
    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "发送人ID（居民=resident.id，其余角色=sys_user.id）")
    private Long senderId;

    @Schema(description = "发送人角色：RESIDENT/STAFF/ADMIN/SUPER_ADMIN（落库时取认证角色）")
    private String senderRole;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "发送时间")
    private LocalDateTime createdAt;
}
