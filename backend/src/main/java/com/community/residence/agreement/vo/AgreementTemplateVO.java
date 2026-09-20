package com.community.residence.agreement.vo;

import com.community.residence.agreement.entity.AgreementTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 租赁协议模板响应 */
@Data
@Schema(description = "租赁协议模板")
public class AgreementTemplateVO {

    @Schema(description = "模板ID")
    private Long id;

    @Schema(description = "归属社区ID（为空=全局通用模板）")
    private Long communityId;

    @Schema(description = "归属社区名称（全局模板为空）")
    private String communityName;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "协议正文")
    private String content;

    @Schema(description = "模板附件原始文件名")
    private String fileName;

    @Schema(description = "模板附件访问地址")
    private String fileUrl;

    @Schema(description = "模板附件大小（字节）")
    private Long fileSize;

    @Schema(description = "是否默认模板：0-否, 1-是")
    private Integer isDefault;

    @Schema(description = "状态：ACTIVE-启用, INACTIVE-停用")
    private String status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static AgreementTemplateVO from(AgreementTemplate entity) {
        AgreementTemplateVO vo = new AgreementTemplateVO();
        vo.setId(entity.getId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setName(entity.getName());
        vo.setContent(entity.getContent());
        vo.setFileName(entity.getFileName());
        vo.setFileUrl(entity.getFileUrl());
        vo.setFileSize(entity.getFileSize());
        vo.setIsDefault(entity.getIsDefault());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
