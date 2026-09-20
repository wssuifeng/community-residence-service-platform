package com.community.residence.agreement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 租赁协议模板创建/更新请求（更新接口复用本 DTO） */
@Data
@Schema(description = "租赁协议模板创建/更新请求")
public class AgreementTemplateDTO {

    @Schema(description = "归属社区ID：为空表示全局通用模板（仅超管可维护）")
    private Long communityId;

    @Schema(description = "模板名称")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称最多 100 字符")
    private String name;

    @Schema(description = "协议正文，支持 {{变量}} 占位：{{社区名称}} {{房屋位置}} {{房号}} {{租客姓名}} "
            + "{{租期开始}} {{租期结束}} {{月租金}} {{押金}} {{签约日期}}；为空时正文以模板附件为准")
    @Size(max = 20000, message = "协议正文过长")
    private String content;

    @Schema(description = "模板附件原始文件名（先经 POST /api/v1/upload 上传取得 URL）")
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    @Schema(description = "模板附件访问地址")
    @Size(max = 500, message = "附件地址过长")
    private String fileUrl;

    @Schema(description = "模板附件大小（字节）")
    private Long fileSize;

    @Schema(description = "是否设为默认模板：1-是；同范围内原默认模板自动取消")
    private Integer isDefault;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
