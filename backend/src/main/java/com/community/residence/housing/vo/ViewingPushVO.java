package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 看房会话 WebSocket 推送载荷（R59，复制 R30 反馈会话 §6.3 的 type + data 包裹） */
@Data
@Schema(description = "看房会话 WebSocket 推送载荷")
public class ViewingPushVO {

    @Schema(description = "消息类型：APPOINTMENT_MESSAGE-会话消息")
    private String type;

    @Schema(description = "载荷内容：APPOINTMENT_MESSAGE 为会话消息")
    private Object data;

    public static ViewingPushVO message(ViewingMessageVO data) {
        ViewingPushVO vo = new ViewingPushVO();
        vo.setType("APPOINTMENT_MESSAGE");
        vo.setData(data);
        return vo;
    }
}
