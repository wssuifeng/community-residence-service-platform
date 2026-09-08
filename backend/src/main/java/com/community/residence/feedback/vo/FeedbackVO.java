package com.community.residence.feedback.vo;

import com.community.residence.feedback.entity.Feedback;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 反馈单响应 */
@Data
@Schema(description = "反馈单")
public class FeedbackVO {

    @Schema(description = "反馈ID")
    private Long id;

    @Schema(description = "反馈人ID")
    private Long residentId;

    @Schema(description = "反馈人姓名")
    private String residentName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "反馈标题")
    private String title;

    @Schema(description = "反馈内容")
    private String content;

    @Schema(description = "反馈类别：SUGGESTION-建议, COMPLAINT-投诉, INQUIRY-咨询")
    private String category;

    @Schema(description = "状态：PENDING-待受理, IN_SESSION-会话中, CLOSED-已关闭")
    private String status;

    @Schema(description = "处理人ID")
    private Long handlerId;

    @Schema(description = "处理人姓名")
    private String handlerName;

    @Schema(description = "提交时间")
    private LocalDateTime createdAt;

    public static FeedbackVO from(Feedback entity) {
        FeedbackVO vo = new FeedbackVO();
        vo.setId(entity.getId());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setCategory(entity.getCategory());
        vo.setStatus(entity.getStatus());
        vo.setHandlerId(entity.getHandlerId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
