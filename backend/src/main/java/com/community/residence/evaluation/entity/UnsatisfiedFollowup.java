package com.community.residence.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 不满意跟进记录（unsatisfied_followup 表） */
@Data
@TableName("unsatisfied_followup")
@Schema(description = "不满意跟进记录")
public class UnsatisfiedFollowup {

    @TableId(type = IdType.AUTO)
    @Schema(description = "跟进ID")
    private Long id;

    @Schema(description = "评价ID")
    private Long evaluationId;

    @Schema(description = "跟进人ID")
    private Long handlerId;

    @Schema(description = "跟进内容")
    private String followupContent;

    @Schema(description = "跟进时间")
    private LocalDateTime followupTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
