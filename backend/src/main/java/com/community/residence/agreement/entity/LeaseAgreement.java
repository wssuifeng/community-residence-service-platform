package com.community.residence.agreement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租约协议（lease_agreement 表）。
 * content 为发起时的正文快照：模板后续修改不影响已签协议；
 * 双方确认各留确认人、确认时间与确认时姓名，构成轻量在线确认的留痕证据。
 */
@Data
@TableName("lease_agreement")
@Schema(description = "租约协议")
public class LeaseAgreement {

    @TableId(type = IdType.AUTO)
    @Schema(description = "协议ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "来源模板ID")
    private Long templateId;

    @Schema(description = "来源模板名称快照")
    private String templateName;

    @Schema(description = "协议标题")
    private String title;

    @Schema(description = "协议正文快照（占位符已渲染）")
    private String content;

    @Schema(description = "模板附件地址快照")
    private String templateFileUrl;

    @Schema(description = "模板附件文件名快照")
    private String templateFileName;

    @Schema(description = "状态：PENDING-待确认, PARTIAL-单方已确认, SIGNED-双方已确认, CANCELLED-已撤回")
    private String status;

    @Schema(description = "居民方居民ID")
    private Long tenantId;

    @Schema(description = "居民方确认人姓名")
    private String tenantConfirmName;

    @Schema(description = "居民方确认时间")
    private LocalDateTime tenantConfirmTime;

    @Schema(description = "管理方确认人用户ID")
    private Long adminId;

    @Schema(description = "管理方确认人姓名")
    private String adminConfirmName;

    @Schema(description = "管理方确认时间")
    private LocalDateTime adminConfirmTime;

    @Schema(description = "撤回原因")
    private String cancelReason;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "发起人用户ID")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
