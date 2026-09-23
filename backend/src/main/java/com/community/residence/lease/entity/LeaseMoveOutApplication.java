package com.community.residence.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 居民自助退租申请（lease_move_out_application 表；R70，状态机 PENDING → APPROVED / REJECTED） */
@Data
@TableName("lease_move_out_application")
@Schema(description = "居民自助退租申请")
public class LeaseMoveOutApplication {

    @TableId(type = IdType.AUTO)
    @Schema(description = "退租申请ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "申请人居民ID")
    private Long residentId;

    @Schema(description = "所属社区ID（数据级权限锚点）")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "申请搬出日期")
    private LocalDate moveOutDate;

    @Schema(description = "退租原因")
    private String reason;

    @Schema(description = "状态：PENDING-待审核, APPROVED-已通过, REJECTED-已驳回")
    private String status;

    @Schema(description = "审核人用户ID")
    private Long reviewerId;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    @Schema(description = "驳回原因（驳回时必填）")
    private String rejectReason;

    @Schema(description = "申请时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
