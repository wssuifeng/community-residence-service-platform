package com.community.residence.messaging.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 通知记录（notification 表；seq 全局递增序号支撑增量拉取，P2 WebSocket 复用） */
@Data
@TableName("notification")
@Schema(description = "通知记录")
public class Notification {

    @TableId(type = IdType.AUTO)
    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "全局递增序号（Redis INCR 生成）")
    private Long seq;

    @Schema(description = "接收人ID（口径由 ownerType 决定：USER=sys_user.id / RESIDENT=resident.id）")
    private Long userId;

    @Schema(description = "通知归属主体类型：USER-sys_user主体（员工/管理员），RESIDENT-居民主体；"
            + "与 userId 组成唯一收件人身份（DEF-067 身份空间分离）")
    private String ownerType;

    @Schema(description = "所属社区ID（可选）")
    private Long communityId;

    @Schema(description = "通知标题")
    private String title;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "通知类型")
    private String type;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源ID")
    private Long sourceId;

    @Schema(description = "推送渠道（逗号分隔）：WEBSOCKET, EMAIL, SMS")
    private String channels;

    @Schema(description = "已读状态：0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
