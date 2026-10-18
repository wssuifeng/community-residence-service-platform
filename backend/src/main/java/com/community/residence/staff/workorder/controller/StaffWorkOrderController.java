package com.community.residence.staff.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.workorder.dto.WorkOrderActionDTO;
import com.community.residence.common.workorder.dto.WorkOrderRemarkDTO;
import com.community.residence.common.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 工单管理控制器：提交/派单/接单/处理/确认/关闭全链路（接口设计.md 9.4.2）。
 * 处置动作的请求体分两类：**可选**（接单/开始处理/确认完成/关闭，{@link WorkOrderRemarkDTO}）
 * 与**必填理由**（完成处理/不满意退回/驳回/取消，{@link WorkOrderActionDTO} 的 remark @NotBlank）。 */
@Tag(name = "工单管理", description = "服务申请与工单流转接口")
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class StaffWorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "接单", description = "已派单 → 已接单；仅被派单的服务人员；请求体可选")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/accept")
    public ApiResponse<Void> accept(@PathVariable Long id, @RequestBody(required = false) @Valid WorkOrderRemarkDTO dto) {
        workOrderService.accept(id, remarkOf(dto));
        return ApiResponse.success();
    }

    @Operation(summary = "开始处理", description = "已接单 → 处理中；请求体可选")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/process")
    public ApiResponse<Void> process(@PathVariable Long id, @RequestBody(required = false) @Valid WorkOrderRemarkDTO dto) {
        workOrderService.process(id, remarkOf(dto));
        return ApiResponse.success();
    }

    @Operation(summary = "完成处理", description = "处理中 → 待确认（居民确认）")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.complete(id, dto.getRemark());
        return ApiResponse.success();
    }

    /* DEF-A：可选请求体端点（accept/process/confirm/close）统一取值——body 缺省即无备注，
       不能让「不传请求体」落到 HttpMessageNotReadableException 变成 400 */
    private String remarkOf(WorkOrderRemarkDTO dto) {
        return dto == null ? null : dto.getRemark();
    }
}
