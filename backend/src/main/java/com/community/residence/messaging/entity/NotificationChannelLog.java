package com.community.residence.messaging.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 通知渠道发送记录（notification_channel_log 表；模拟渠道触发留痕，R51） */
@Data
@TableName("notification_channel_log")
@Schema(description = "通知渠道发送记录")
public class NotificationChannelLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "通知ID")
    private Long notificationId;

    @Schema(description = "渠道：WEBSOCKET-站内, EMAIL-邮件, SMS-短信")
    private String channel;

    @Schema(description = "发送状态：SUCCESS-成功, FAILED-失败")
    private String status;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "发送时间")
    private LocalDateTime sentTime;
}
