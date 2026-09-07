package com.community.residence.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 反馈单（feedback 表；状态机：PENDING 待受理 → IN_SESSION 会话中 → CLOSED 已办结） */
@Data
@TableName("feedback")
@Schema(description = "反馈单")
public class Feedback {

    @TableId(type = IdType.AUTO)
    @Schema(description = "反馈ID")
    private Long id;

    @Schema(description = "反馈人ID")
    private Long residentId;

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

    @Schema(description = "提交时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
