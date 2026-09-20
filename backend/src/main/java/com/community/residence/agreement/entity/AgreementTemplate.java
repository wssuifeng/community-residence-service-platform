package com.community.residence.agreement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 租赁协议模板（agreement_template 表；community_id 为空表示全局通用模板） */
@Data
@TableName("agreement_template")
@Schema(description = "租赁协议模板")
public class AgreementTemplate {

    @TableId(type = IdType.AUTO)
    @Schema(description = "模板ID")
    private Long id;

    @Schema(description = "所属社区ID（为空=全局通用模板，各社区可用）")
    private Long communityId;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "协议正文（支持 {{变量}} 占位）")
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
}
