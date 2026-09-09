package com.community.residence.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租期到期提醒去重（lease_reminder 表；唯一键 lease_id + status）。
 * status 存到期标注值（EXPIRING/EXPIRED），同一租住记录同一标注仅提醒一次（R15）；
 * 管理员手动重发语义为插入前删除旧记录。
 */
@Data
@TableName("lease_reminder")
@Schema(description = "租期到期提醒去重记录")
public class LeaseReminder {

    @TableId(type = IdType.AUTO)
    @Schema(description = "提醒ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "到期标注：EXPIRING-即将到期, EXPIRED-已到期")
    private String status;

    @Schema(description = "提醒时间")
    private LocalDateTime remindTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
