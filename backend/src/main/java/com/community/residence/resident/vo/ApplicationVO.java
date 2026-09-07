package com.community.residence.resident.vo;

import com.community.residence.resident.entity.ResidenceApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 入住申请响应 */
@Data
@Schema(description = "入住申请")
public class ApplicationVO {

    @Schema(description = "申请ID")
    private Long id;

    @Schema(description = "申请人ID")
    private Long residentId;

    @Schema(description = "申请人姓名")
    private String residentName;

    @Schema(description = "申请社区ID")
    private Long communityId;

    @Schema(description = "申请房屋ID")
    private Long houseId;

    @Schema(description = "房屋位置（楼栋-单元-房号）")
    private String houseLocation;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    private String relationType;

    @Schema(description = "状态：PENDING-待审核, APPROVED-已通过, REJECTED-已拒绝")
    private String status;

    @Schema(description = "申请说明")
    private String remark;

    @Schema(description = "审核意见")
    private String reviewRemark;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    @Schema(description = "申请时间")
    private LocalDateTime createdAt;

    public static ApplicationVO from(ResidenceApplication entity) {
        ApplicationVO vo = new ApplicationVO();
        vo.setId(entity.getId());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseId(entity.getHouseId());
        vo.setRelationType(entity.getRelationType());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setReviewRemark(entity.getReviewRemark());
        vo.setReviewTime(entity.getReviewTime());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
