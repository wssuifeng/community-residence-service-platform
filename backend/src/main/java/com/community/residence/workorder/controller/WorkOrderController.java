package com.community.residence.workorder.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.workorder.dto.WorkOrderActionDTO;
import com.community.residence.workorder.service.WorkOrderService;
import com.community.residence.workorder.vo.AttachmentVO;
import com.community.residence.workorder.vo.ProcessRecordVO;
import com.community.residence.workorder.vo.WorkOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 工单管理控制器：提交/派单/接单/处理/确认/关闭全链路（接口设计.md 9.4.2） */
@Tag(name = "工单管理", description = "服务申请与工单流转接口")
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "提交工单", description = "居民端；初始待受理")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<WorkOrderVO> create(@RequestBody @Valid CreateWorkOrderDTO dto) {
        return ApiResponse.success(workOrderService.create(dto));
    }

    @Operation(summary = "更新工单", description = "居民仅待受理/待派单状态可修改本人工单")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<WorkOrderVO> update(@PathVariable Long id, @RequestBody @Valid CreateWorkOrderDTO dto) {
        return ApiResponse.success(workOrderService.update(id, dto));
    }

    @Operation(summary = "查询工单详情", description = "居民限本人；服务人员限派给本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<WorkOrderVO> getById(@PathVariable Long id) {
        return ApiResponse.success(workOrderService.getById(id));
    }

    @Operation(summary = "上传工单附件", description = "仅工单提交人；图片 ≤5MB，文档 ≤10MB")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentVO> uploadAttachment(@PathVariable Long id,
                                                      @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(workOrderService.uploadAttachment(id, file));
    }

    @Operation(summary = "工单附件列表", description = "访问权限与工单详情同口径")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/attachments")
    public ApiResponse<List<AttachmentVO>> attachments(@PathVariable Long id) {
        return ApiResponse.success(workOrderService.attachments(id));
    }

    @Operation(summary = "工单列表（分页）", description = "按角色自动收敛数据范围")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<WorkOrderVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String priority,
                                                 @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.success(workOrderService.page(page, size, status, priority, categoryId, keyword));
    }

    @Operation(summary = "派单", description = "待受理/待派单 → 已派单；支持改派")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/assign")
    public ApiResponse<Void> assign(@PathVariable Long id, @RequestBody @Valid AssignWorkOrderDTO dto) {
        workOrderService.assign(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "接单", description = "已派单 → 已接单；仅被派单的服务人员")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/accept")
    public ApiResponse<Void> accept(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.accept(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "开始处理", description = "已接单 → 处理中")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/process")
    public ApiResponse<Void> process(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.process(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "完成处理", description = "处理中 → 待确认（居民确认）")
    @PreAuthorize("hasRole('STAFF')")
    @PatchMapping("/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.complete(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "确认完成", description = "待确认 → 已完成；仅工单提交人")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.confirm(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "关闭工单", description = "已完成 → 已关闭（终态）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.close(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "驳回工单", description = "待受理/待派单 → 已驳回（终态）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.reject(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "取消工单", description = "待受理/待派单/已派单/已接单 → 已取消；仅提交人")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.cancel(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "工单处理时间线")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/timeline")
    public ApiResponse<List<ProcessRecordVO>> timeline(@PathVariable Long id) {
        return ApiResponse.success(workOrderService.timeline(id));
    }
}
