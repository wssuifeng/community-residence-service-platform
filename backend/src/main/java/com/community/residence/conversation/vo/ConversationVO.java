package com.community.residence.conversation.vo;

import com.community.residence.conversation.entity.Conversation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话响应（列表/详情元素：类型/标题/关联对象/状态/最后消息摘要/未读数，R63+R68 契约字段） */
@Data
@Schema(description = "会话")
public class ConversationVO {

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

    @Schema(description = "最后一条消息摘要")
    private String lastMessage;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMessageAt;

    @Schema(description = "未读消息数（按参与者已读游标计数）")
    private Long unreadCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "参与者数量（会话详情返回）")
    private Integer participantCount;

    @Schema(description = "参与者概要（会话详情返回，群成员展示用）")
    private java.util.List<ConversationParticipantVO> participants;

    public static ConversationVO from(Conversation entity) {
        ConversationVO vo = new ConversationVO();
        vo.setId(entity.getId());
        vo.setType(entity.getType());
        vo.setTitle(entity.getTitle());
        vo.setCommunityId(entity.getCommunityId());
        vo.setRelatedId(entity.getRelatedId());
        vo.setStatus(entity.getStatus());
        vo.setClosedAt(entity.getClosedAt());
        vo.setLastMessage(entity.getLastMessage());
        vo.setLastMessageAt(entity.getLastMessageAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
