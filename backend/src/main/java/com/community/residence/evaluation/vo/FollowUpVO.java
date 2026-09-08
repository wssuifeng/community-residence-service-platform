package com.community.residence.evaluation.vo;

import com.community.residence.evaluation.entity.UnsatisfiedFollowup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 不满意跟进记录响应 */
@Data
@Schema(description = "不满意跟进记录")
public class FollowUpVO {

    @Schema(description = "跟进ID")
    private Long id;

    @Schema(description = "评价ID")
    private Long evaluationId;

    @Schema(description = "跟进人ID")
    private Long handlerId;

    @Schema(description = "跟进人姓名")
    private String handlerName;

    @Schema(description = "跟进内容")
    private String followupContent;

    @Schema(description = "跟进时间")
    private LocalDateTime followupTime;

    public static FollowUpVO from(UnsatisfiedFollowup entity) {
        FollowUpVO vo = new FollowUpVO();
        vo.setId(entity.getId());
        vo.setEvaluationId(entity.getEvaluationId());
        vo.setHandlerId(entity.getHandlerId());
        vo.setFollowupContent(entity.getFollowupContent());
        vo.setFollowupTime(entity.getFollowupTime());
        return vo;
    }
}
