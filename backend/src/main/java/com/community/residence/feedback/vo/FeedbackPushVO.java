package com.community.residence.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 反馈会话 WebSocket 推送载荷（接口设计 §6.3：type + data 包裹） */
@Data
@Schema(description = "反馈会话 WebSocket 推送载荷")
public class FeedbackPushVO {

    @Schema(description = "消息类型：FEEDBACK_MESSAGE")
    private String type;

    @Schema(description = "会话消息内容")
    private MessageVO data;

    public static FeedbackPushVO message(MessageVO data) {
        FeedbackPushVO vo = new FeedbackPushVO();
        vo.setType("FEEDBACK_MESSAGE");
        vo.setData(data);
        return vo;
    }
}
