package com.community.residence.agreement.vo;

import com.community.residence.agreement.entity.LeaseAgreement;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 租约协议响应 */
@Data
@Schema(description = "租约协议")
public class LeaseAgreementVO {

    @Schema(description = "协议ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "来源模板ID")
    private Long templateId;

    @Schema(description = "来源模板名称")
    private String templateName;

    @Schema(description = "协议标题")
    private String title;

    @Schema(description = "协议正文快照")
    private String content;

    @Schema(description = "模板附件地址")
    private String templateFileUrl;

    @Schema(description = "模板附件文件名")
    private String templateFileName;

    @Schema(description = "状态：PENDING-待确认, PARTIAL-单方已确认, SIGNED-双方已确认, CANCELLED-已撤回")
    private String status;

    @Schema(description = "居民方居民ID")
    private Long tenantId;

    @Schema(description = "居民方确认人姓名")
    private String tenantConfirmName;

    @Schema(description = "居民方确认时间")
    private LocalDateTime tenantConfirmTime;

    @Schema(description = "管理方确认人姓名")
    private String adminConfirmName;

    @Schema(description = "管理方确认时间")
    private LocalDateTime adminConfirmTime;

    @Schema(description = "撤回原因")
    private String cancelReason;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static LeaseAgreementVO from(LeaseAgreement entity) {
        LeaseAgreementVO vo = new LeaseAgreementVO();
        vo.setId(entity.getId());
        vo.setLeaseId(entity.getLeaseId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setTemplateId(entity.getTemplateId());
        vo.setTemplateName(entity.getTemplateName());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setTemplateFileUrl(entity.getTemplateFileUrl());
        vo.setTemplateFileName(entity.getTemplateFileName());
        vo.setStatus(entity.getStatus());
        vo.setTenantId(entity.getTenantId());
        vo.setTenantConfirmName(entity.getTenantConfirmName());
        vo.setTenantConfirmTime(entity.getTenantConfirmTime());
        vo.setAdminConfirmName(entity.getAdminConfirmName());
        vo.setAdminConfirmTime(entity.getAdminConfirmTime());
        vo.setCancelReason(entity.getCancelReason());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
