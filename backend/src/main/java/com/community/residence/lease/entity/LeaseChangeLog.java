package com.community.residence.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租约属性变更历史（lease_change_log 表）。
 * 与 sys_operation_log 的分工：操作日志记「谁做了什么」（操作级，供安全审计），
 * 本表记「哪个字段从什么变成什么」（字段级，供租约管理追溯）。
 */
@Data
@TableName("lease_change_log")
@Schema(description = "租约属性变更历史")
public class LeaseChangeLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "变更记录ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "变更类型：CREATE-登记, ATTRIBUTE-属性变更, RENEW-续租, STATUS-状态流转, AGREEMENT-协议签约")
    private String changeType;

    @Schema(description = "字段名（非单字段变更时为 NULL）")
    private String fieldName;

    @Schema(description = "字段中文名")
    private String fieldLabel;

    @Schema(description = "变更前值")
    private String oldValue;

    @Schema(description = "变更后值")
    private String newValue;

    @Schema(description = "变更原因/备注")
    private String reason;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名（快照）")
    private String operatorName;

    @Schema(description = "操作人身份：ADMIN-管理方, RESIDENT-居民, SYSTEM-系统任务")
    private String operatorType;

    @Schema(description = "变更时间")
    private LocalDateTime createdAt;
}
