package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单（work_order 表；状态机见 WorkOrderStatus 常量类） */
@Data
@TableName("work_order")
@Schema(description = "工单")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "工单编号（WO + 日期 + 序号）")
    private String orderNo;

    @Schema(description = "提交人ID")
    private Long residentId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "服务类别ID")
    private Long categoryId;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "服务地址")
    private String address;

    @Schema(description = "状态：PENDING/TO_ASSIGN/ASSIGNED/ACCEPTED/IN_PROGRESS/TO_CONFIRM/COMPLETED/CLOSED/REJECTED/CANCELLED")
    private String status;

    @Schema(description = "优先级：LOW-低, NORMAL-普通, HIGH-高, URGENT-紧急")
    private String priority;

    @Schema(description = "提交时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
