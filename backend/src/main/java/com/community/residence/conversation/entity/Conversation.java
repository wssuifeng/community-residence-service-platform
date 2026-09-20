package com.community.residence.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 多方会话（conversation 表；VIEWING_GROUP 看房群聊 / DIRECT 居民-管理员直通，R63） */
@Data
@TableName("conversation")
@Schema(description = "多方会话")
public class Conversation {

    @TableId(type = IdType.AUTO)
    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "会话类型：VIEWING_GROUP-看房预约群聊, DIRECT-居民与管理员直通")
    private String type;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "关联业务对象ID（VIEWING_GROUP=看房预约ID；DIRECT 为空）")
    private Long relatedId;

    @Schema(description = "最后一条消息摘要（冗余，发消息时维护）")
    private String lastMessage;

    @Schema(description = "最后一条消息时间（冗余，发消息时维护）")
    private LocalDateTime lastMessageAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
