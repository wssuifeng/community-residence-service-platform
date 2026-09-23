package com.community.residence.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 多方会话（conversation 表；VIEWING_GROUP 看房群聊 / LEASE_GROUP 租约群聊 / DIRECT 直通，R63+R68） */
@Data
@TableName("conversation")
@Schema(description = "多方会话")
public class Conversation {

    @TableId(type = IdType.AUTO)
    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "会话类型：VIEWING_GROUP-看房预约群聊, LEASE_GROUP-租约群聊, DIRECT-居民与管理员直通")
    private String type;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "关联业务对象ID（VIEWING_GROUP=看房预约ID；LEASE_GROUP=租约ID；DIRECT 为空）")
    private Long relatedId;

    @Schema(description = "会话状态（R68）：ACTIVE-进行中, CLOSED-已关闭（只读留档，停收停发）")
    private String status;

    @Schema(description = "会话关闭时间（R68）")
    private LocalDateTime closedAt;

    @Schema(description = "最后一条消息摘要（冗余，发消息时维护）")
    private String lastMessage;

    @Schema(description = "最后一条消息时间（冗余，发消息时维护）")
    private LocalDateTime lastMessageAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
