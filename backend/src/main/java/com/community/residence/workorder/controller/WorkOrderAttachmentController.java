package com.community.residence.workorder.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工单附件删除（接口设计.md 9.4.3.2；路径前缀与 /work-orders 不同，独立控制器承载） */
@Tag(name = "工单管理", description = "服务申请与工单流转接口")
@RestController
@RequestMapping("/api/v1/work-order-attachments")
@RequiredArgsConstructor
public class WorkOrderAttachmentController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "删除工单附件", description = "居民限提交人，服务人员限被派单人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAttachment(@PathVariable Long id) {
        workOrderService.deleteAttachment(id);
        return ApiResponse.success();
    }
}
