package com.community.residence.common.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.common.workorder.dto.WorkOrderActionDTO;
import com.community.residence.common.workorder.dto.WorkOrderRemarkDTO;
import com.community.residence.common.workorder.service.WorkOrderService;
import com.community.residence.common.workorder.vo.AttachmentVO;
import com.community.residence.common.workorder.vo.ProcessRecordVO;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

/** 工单管理控制器：提交/派单/接单/处理/确认/关闭全链路（接口设计.md 9.4.2）。
 * 处置动作的请求体分两类：**可选**（接单/开始处理/确认完成/关闭，{@link WorkOrderRemarkDTO}）
 * 与**必填理由**（完成处理/不满意退回/驳回/取消，{@link WorkOrderActionDTO} 的 remark @NotBlank）。 */
@Tag(name = "工单管理", description = "服务申请与工单流转接口")
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderQueryController {

    private final WorkOrderService workOrderService;

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

    @Operation(summary = "上传工单附件", description = "居民限提交人，服务人员限被派单人；图片 ≤5MB，文档 ≤10MB")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
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

    @Operation(summary = "工单列表（分页）", description = "按角色自动收敛数据范围；status/statuses 单选与多选二选一，statuses 逗号分隔；"
            + "assigneeId 按处理人筛选；sort=DEFAULT（ID 倒序）/WAIT_DESC（等待时长降序）/PRIORITY（紧急优先）")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<WorkOrderVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String statuses,
                                                 @RequestParam(required = false) String priority,
                                                 @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startTime,
                                                 @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endTime,
                                                 @RequestParam(required = false) Long assigneeId,
                                                 @RequestParam(required = false) String sort) {
        return ApiResponse.success(workOrderService.page(page, size, status, statuses,
                priority, categoryId, keyword, startTime, endTime, assigneeId, sort));
    }

    @Operation(summary = "工单处理时间线")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/timeline")
    public ApiResponse<List<ProcessRecordVO>> timeline(@PathVariable Long id) {
        return ApiResponse.success(workOrderService.timeline(id));
    }
}
