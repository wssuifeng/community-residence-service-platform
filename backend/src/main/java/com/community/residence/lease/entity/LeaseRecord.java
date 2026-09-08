package com.community.residence.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 租住记录（lease_record 表；「即将到期/已到期」为定时任务标注语义，非状态值） */
@Data
@TableName("lease_record")
@Schema(description = "租住记录")
public class LeaseRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "租住记录ID")
    private Long id;

    @Schema(description = "租客ID")
    private Long tenantId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "租期开始日期")
    private LocalDate startDate;

    @Schema(description = "租期结束日期")
    private LocalDate endDate;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    private BigDecimal deposit;

    @Schema(description = "状态：PENDING-待生效, ACTIVE-租住中, MOVED_OUT-已迁出, ARCHIVED-已归档, REJECTED-已拒绝")
    private String status;

    @Schema(description = "合同附件URL")
    private String contractUrl;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
