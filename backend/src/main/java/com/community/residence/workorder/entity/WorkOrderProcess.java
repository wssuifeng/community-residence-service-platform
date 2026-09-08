package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单处理记录（work_order_process 表，时间线，仅追加） */
@Data
@TableName("work_order_process")
@Schema(description = "工单处理记录")
public class WorkOrderProcess {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人类型：RESIDENT-居民, STAFF-服务人员, ADMIN-管理员")
    private String operatorType;

    @Schema(description = "操作动作")
    private String action;

    @Schema(description = "变更前状态")
    private String oldStatus;

    @Schema(description = "变更后状态")
    private String newStatus;

    @Schema(description = "处理内容")
    private String content;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
