package com.community.residence.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话参与者（conversation_participant 表；user_id 多态引用不设外键，R63） */
@Data
@TableName("conversation_participant")
@Schema(description = "会话参与者")
public class ConversationParticipant {

    @TableId(type = IdType.AUTO)
    @Schema(description = "参与者ID")
    private Long id;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "参与者ID（居民=resident.id，其余角色=sys_user.id）")
    private Long userId;

    @Schema(description = "已读游标（最后已读消息ID，0=全部未读）")
    private Long lastReadMessageId;

    @Schema(description = "入会时间")
    private LocalDateTime createdAt;
}
