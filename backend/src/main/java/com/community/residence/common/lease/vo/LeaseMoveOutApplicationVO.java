package com.community.residence.common.lease.vo;

import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 退租申请响应（R70：居民端进度查看 + 管理端审核列表） */
@Data
@Schema(description = "退租申请")
public class LeaseMoveOutApplicationVO {

    @Schema(description = "退租申请ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "申请人居民ID")
    private Long residentId;

    @Schema(description = "申请人姓名")
    private String residentName;

    @Schema(description = "所属小区ID")
    private Long communityId;

    @Schema(description = "小区名称")
    private String communityName;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "房屋位置（楼栋-单元-房号）")
    private String houseLocation;

    @Schema(description = "申请搬出日期")
    private LocalDate moveOutDate;

    @Schema(description = "退租原因")
    private String reason;

    @Schema(description = "状态：PENDING-待审核, APPROVED-已通过, REJECTED-已驳回")
    private String status;

    @Schema(description = "审核人用户ID")
    private Long reviewerId;

    @Schema(description = "审核人姓名")
    private String reviewerName;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "申请时间")
    private LocalDateTime createdAt;

    public static LeaseMoveOutApplicationVO from(LeaseMoveOutApplication entity) {
        LeaseMoveOutApplicationVO vo = new LeaseMoveOutApplicationVO();
        vo.setId(entity.getId());
        vo.setLeaseId(entity.getLeaseId());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseId(entity.getHouseId());
        vo.setMoveOutDate(entity.getMoveOutDate());
        vo.setReason(entity.getReason());
        vo.setStatus(entity.getStatus());
        vo.setReviewerId(entity.getReviewerId());
        vo.setReviewTime(entity.getReviewTime());
        vo.setRejectReason(entity.getRejectReason());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
