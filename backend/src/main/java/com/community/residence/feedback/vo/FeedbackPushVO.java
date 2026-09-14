package com.community.residence.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 反馈会话 WebSocket 推送载荷（接口设计 §6.3：type + data 包裹） */
@Data
@Schema(description = "反馈会话 WebSocket 推送载荷")
public class FeedbackPushVO {

    @Schema(description = "消息类型：FEEDBACK_MESSAGE-会话消息, FEEDBACK_STATUS-状态变更")
    private String type;

    @Schema(description = "载荷内容：FEEDBACK_MESSAGE 为会话消息，FEEDBACK_STATUS 为状态变更")
    private Object data;

    public static FeedbackPushVO message(MessageVO data) {
        FeedbackPushVO vo = new FeedbackPushVO();
        vo.setType("FEEDBACK_MESSAGE");
        vo.setData(data);
        return vo;
    }

    /** 办结状态变更载荷（DEF-027：对端实时感知办结） */
    public static FeedbackPushVO status(Long feedbackId, String status) {
        FeedbackPushVO vo = new FeedbackPushVO();
        vo.setType("FEEDBACK_STATUS");
        FeedbackStatusVO payload = new FeedbackStatusVO();
        payload.setFeedbackId(feedbackId);
        payload.setStatus(status);
        vo.setData(payload);
        return vo;
    }

    /** 反馈状态变更载荷（data 通道）：feedbackId + 最新状态 */
    @Data
    @EqualsAndHashCode(callSuper = false)
    @Schema(description = "反馈状态变更载荷")
    public static class FeedbackStatusVO {

        @Schema(description = "反馈ID")
        private Long feedbackId;

        @Schema(description = "最新状态：CLOSED-已办结")
        private String status;
    }
}
