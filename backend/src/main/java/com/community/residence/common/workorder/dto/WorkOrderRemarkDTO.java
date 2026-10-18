package com.community.residence.common.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单处置备注（**可选**请求体）：接单 / 开始处理 / 确认完成 / 关闭工单四个动作共用。
 * 这四个动作的备注只是可选补充说明，业务上不要求填写，故请求体与 remark 均可缺省
 * （DEF-A：原先与「须填理由」的动作共用带 {@code @NotBlank} 的 WorkOrderActionDTO，
 * 前端 confirm/close 不传 body 时被 HttpMessageNotReadableException 拦成
 * 「400 请求体格式不合法」，工单无法确认完成、无法归档关闭）。
 * 须填理由的动作（完成处理 solution / 不满意退回 / 驳回 / 取消）仍用
 * {@link WorkOrderActionDTO}，其 {@code @NotBlank} 必填语义未丢失。
 */
@Data
@Schema(description = "工单处置备注（可选请求体）")
public class WorkOrderRemarkDTO {

    @Schema(description = "处置说明（可选；最长 500 字符）")
    @Size(max = 500, message = "处置说明最多 500 字符")
    private String remark;
}
