package com.community.residence.housing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 看房会话消息（viewing_message 表；R59，sender_id 多态引用：居民=resident.id，带看人=sys_user.id） */
@Data
@TableName("viewing_message")
@Schema(description = "看房会话消息")
public class ViewingMessage {

    @TableId(type = IdType.AUTO)
    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "看房预约ID")
    private Long appointmentId;

    @Schema(description = "发送人ID（预约居民=resident.id，带看人=sys_user.id）")
    private Long senderId;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "发送时间")
    private LocalDateTime createdAt;
}
