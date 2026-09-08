package com.community.residence.resident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 入住申请（residence_application 表；状态机 PENDING → APPROVED / REJECTED） */
@Data
@TableName("residence_application")
@Schema(description = "入住申请")
public class ResidenceApplication {

    @TableId(type = IdType.AUTO)
    @Schema(description = "申请ID")
    private Long id;

    @Schema(description = "申请人ID")
    private Long residentId;

    @Schema(description = "申请社区ID")
    private Long communityId;

    @Schema(description = "申请房屋ID")
    private Long houseId;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    private String relationType;

    @Schema(description = "状态：PENDING-待审核, APPROVED-已通过, REJECTED-已拒绝")
    private String status;

    @Schema(description = "申请说明")
    private String remark;

    @Schema(description = "审核人ID")
    private Long reviewerId;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    @Schema(description = "审核意见")
    private String reviewRemark;

    @Schema(description = "申请时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
