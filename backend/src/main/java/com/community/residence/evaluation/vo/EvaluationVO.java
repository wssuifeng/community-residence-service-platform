package com.community.residence.evaluation.vo;

import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单评价响应 */
@Data
@Schema(description = "工单评价")
public class EvaluationVO {

    @Schema(description = "评价ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "工单编号")
    private String workOrderNo;

    @Schema(description = "评价人ID")
    private Long residentId;

    @Schema(description = "评价人姓名")
    private String residentName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "评分：1-5星")
    private Integer rating;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "标签（逗号分隔）")
    private String tags;

    @Schema(description = "是否满意：0-不满意, 1-满意")
    private Integer isSatisfied;

    @Schema(description = "评价时间")
    private LocalDateTime createdAt;

    public static EvaluationVO from(WorkOrderEvaluation entity) {
        EvaluationVO vo = new EvaluationVO();
        vo.setId(entity.getId());
        vo.setWorkOrderId(entity.getWorkOrderId());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setRating(entity.getRating());
        vo.setContent(entity.getContent());
        vo.setTags(entity.getTags());
        vo.setIsSatisfied(entity.getIsSatisfied());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
