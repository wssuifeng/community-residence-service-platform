package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 工单处置请求（接单/处理中/完成/确认/关闭/驳回/取消共用，动作语义由端点决定；
 * 完成接口的 solution 为处理结果说明） */
@Data
@Schema(description = "工单处置请求")
public class WorkOrderActionDTO {

    @Schema(description = "处置说明（完成接口为处理结果）")
    @NotBlank(message = "处置说明不能为空")
    @Size(max = 500, message = "处置说明最多 500 字符")
    private String remark;
}
