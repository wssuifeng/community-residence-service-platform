package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单派单关系（work_order_assignment 表；同工单可多次改派，最新一条为当前处理人） */
@Data
@TableName("work_order_assignment")
@Schema(description = "工单派单关系")
public class WorkOrderAssignment {

    @TableId(type = IdType.AUTO)
    @Schema(description = "派单ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "服务人员ID")
    private Long assigneeId;

    @Schema(description = "派单人ID")
    private Long assignerId;

    @Schema(description = "派单时间")
    private LocalDateTime assignTime;

    @Schema(description = "接单时间")
    private LocalDateTime acceptTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
