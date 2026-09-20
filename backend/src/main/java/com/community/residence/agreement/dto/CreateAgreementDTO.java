package com.community.residence.agreement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发起租约协议请求（管理方按模板生成协议并送居民确认） */
@Data
@Schema(description = "发起租约协议请求")
public class CreateAgreementDTO {

    @Schema(description = "租住记录ID")
    @NotNull(message = "租住记录ID不能为空")
    private Long leaseId;

    @Schema(description = "协议模板ID；为空时取该社区默认模板")
    private Long templateId;

    @Schema(description = "协议标题；为空时按「房屋位置 租赁协议」生成")
    @Size(max = 150, message = "协议标题最多 150 字符")
    private String title;

    @Schema(description = "备注（随协议一并展示给居民）")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
