package com.community.residence.common.community.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 删除前实际关联摘要；确认版本仅用于检测预览后变化，不作为权限凭据。 */
@Data
@Schema(description="小区删除影响")
public class CommunityDeletionImpactVO {
    @Schema(description="小区ID") private Long communityId;
    @Schema(description="小区名称") private String communityName;
    @Schema(description="房屋总数，含已软删除") private long houses;
    @Schema(description="居住关系总数，含历史") private long relations;
    @Schema(description="有效居住关系数") private long activeRelations;
    @Schema(description="租约总数，含历史") private long leases;
    @Schema(description="待审核、待签约或有效租约数") private long activeLeases;
    @Schema(description="入住申请总数") private long applications;
    @Schema(description="待审核申请数") private long pendingApplications;
    @Schema(description="支付单总数") private long payments;
    @Schema(description="退款单总数") private long refunds;
    @Schema(description="超管经完整影响预览和明确确认可删除；有效链不构成硬禁止") private boolean deletable;
    @Schema(description="兼容字段；有效关联由影响数强警示") private String blockingReason;
    @Schema(description="关联摘要确认版本；变化后须重新预览") private String confirmationVersion;
}
