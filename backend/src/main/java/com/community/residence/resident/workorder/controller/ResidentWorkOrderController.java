package com.community.residence.resident.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.common.workorder.dto.WorkOrderActionDTO;
import com.community.residence.common.workorder.dto.WorkOrderRemarkDTO;
import com.community.residence.common.workorder.service.WorkOrderService;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
public class ResidentWorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "提交工单", description = "居民端；初始待受理")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<WorkOrderVO> create(@RequestBody @Valid CreateWorkOrderDTO dto) {
        return ApiResponse.success(workOrderService.create(dto));
    }

    @Operation(summary = "确认完成", description = "待确认 → 已完成；仅工单提交人；**请求体可选**（DEF-A：前端不传 body）")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody(required = false) @Valid WorkOrderRemarkDTO dto) {
        workOrderService.confirm(id, remarkOf(dto));
        return ApiResponse.success();
    }

    @Operation(summary = "居民不满意退回处理中", description = "待确认 → 处理中；仅工单提交人，须填原因（R22）")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/return")
    public ApiResponse<Void> returnBack(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.returnBack(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "取消工单", description = "待受理/待派单/已派单/已接单 → 已取消；仅提交人")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.cancel(id, dto.getRemark());
        return ApiResponse.success();
    }

    /* DEF-A：可选请求体端点（accept/process/confirm/close）统一取值——body 缺省即无备注，
       不能让「不传请求体」落到 HttpMessageNotReadableException 变成 400 */
    private String remarkOf(WorkOrderRemarkDTO dto) {
        return dto == null ? null : dto.getRemark();
    }
}
