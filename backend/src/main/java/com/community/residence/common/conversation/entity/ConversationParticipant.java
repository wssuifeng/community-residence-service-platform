package com.community.residence.common.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话参与者（conversation_participant 表；user_id 多态引用不设外键，R63。
 * DEF-075：user_id 必须与 owner_type 成对使用——居民 1 与 sys_user 1 会撞号，
 * 仅按 user_id 判定会让另一身份空间主体越权进入会话。 */
@Data
@TableName("conversation_participant")
@Schema(description = "会话参与者")
public class ConversationParticipant {

    @TableId(type = IdType.AUTO)
    @Schema(description = "参与者ID")
    private Long id;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "参与者身份空间：USER=sys_user 主体（STAFF/ADMIN/SUPER_ADMIN）/ RESIDENT=resident 主体")
    private String ownerType;

    @Schema(description = "参与者ID（owner_type=RESIDENT 时为 resident.id，USER 时为 sys_user.id）")
    private Long userId;

    @Schema(description = "已读游标（最后已读消息ID，0=全部未读）")
    private Long lastReadMessageId;

    @Schema(description = "入会时间")
    private LocalDateTime createdAt;
}
