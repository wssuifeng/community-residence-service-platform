package com.community.residence.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单评价（work_order_evaluation 表；一单一评，唯一约束） */
@Data
@TableName("work_order_evaluation")
@Schema(description = "工单评价")
public class WorkOrderEvaluation {

    @TableId(type = IdType.AUTO)
    @Schema(description = "评价ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "评价人ID")
    private Long residentId;

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
}
