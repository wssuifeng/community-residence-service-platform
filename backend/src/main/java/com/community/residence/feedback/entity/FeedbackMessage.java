package com.community.residence.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 反馈会话消息（feedback_message 表；P1 HTTP 轮询、P2 升级 WebSocket，按 created_at 增量拉取） */
@Data
@TableName("feedback_message")
@Schema(description = "反馈会话消息")
public class FeedbackMessage {

    @TableId(type = IdType.AUTO)
    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "反馈ID")
    private Long feedbackId;

    @Schema(description = "发送人ID")
    private Long senderId;

    @Schema(description = "发送人类型：RESIDENT-居民, ADMIN-管理员")
    private String senderType;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "父消息ID（回复引用）")
    private Long parentId;

    @Schema(description = "发送时间")
    private LocalDateTime createdAt;
}
