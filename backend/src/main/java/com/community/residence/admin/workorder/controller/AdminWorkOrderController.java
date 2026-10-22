package com.community.residence.admin.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.common.workorder.dto.WorkOrderActionDTO;
import com.community.residence.common.workorder.dto.WorkOrderRemarkDTO;
import com.community.residence.common.workorder.service.WorkOrderService;
import com.community.residence.common.workorder.vo.StaffOptionVO;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工单管理控制器：提交/派单/接单/处理/确认/关闭全链路（接口设计.md 9.4.2）。
 * 处置动作的请求体分两类：**可选**（接单/开始处理/确认完成/关闭，{@link WorkOrderRemarkDTO}）
 * 与**必填理由**（完成处理/不满意退回/驳回/取消，{@link WorkOrderActionDTO} 的 remark @NotBlank）。 */
@Tag(name = "工单管理", description = "服务申请与工单流转接口")
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class AdminWorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "可派单服务人员选项", description = "启用状态 STAFF，按调度推荐档位排序（常驻本小区且擅长该类别优先），"
            + "同在手工单数升序；返回档位、小区/类别匹配、今日班次与在手工单数（R20，V19）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/assignable-staff")
    public ApiResponse<List<StaffOptionVO>> assignableStaff(
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) Long categoryId) {
        return ApiResponse.success(workOrderService.assignableStaff(communityId, categoryId));
    }

    @Operation(summary = "管理端受理", description = "待受理 → 待派单（R74/DEF-071 新增端点）；同一事务自动建立工单临时群聊"
            + "（居民提交人 + 受理管理员入群），响应回带 conversationId；仅待受理可受理，重复受理返回 5302；请求体可选")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/intake")
    public ApiResponse<WorkOrderVO> intake(@PathVariable Long id,
                                           @RequestBody(required = false) @Valid WorkOrderRemarkDTO dto) {
        return ApiResponse.success(workOrderService.intake(id, remarkOf(dto)));
    }

    @Operation(summary = "派单", description = "待受理/待派单 → 已派单；支持改派；被派服务人员自动加入工单临时群聊（R74）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/assign")
    public ApiResponse<Void> assign(@PathVariable Long id, @RequestBody @Valid AssignWorkOrderDTO dto) {
        workOrderService.assign(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "关闭工单", description = "已完成 → 已关闭（终态）；**请求体可选**（DEF-A：前端不传 body）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id, @RequestBody(required = false) @Valid WorkOrderRemarkDTO dto) {
        workOrderService.close(id, remarkOf(dto));
        return ApiResponse.success();
    }

    @Operation(summary = "驳回工单", description = "待受理/待派单 → 已驳回（终态）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody @Valid WorkOrderActionDTO dto) {
        workOrderService.reject(id, dto.getRemark());
        return ApiResponse.success();
    }

    @Operation(summary = "打开或恢复工单沟通群", description = "仅管辖管理方；已受理及后续状态可幂等补群，关闭工单只读，不改变工单状态")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/conversation")
    public ApiResponse<WorkOrderVO> ensureConversation(@PathVariable Long id) {
        return ApiResponse.success(workOrderService.ensureConversation(id));
    }

    /* DEF-A：可选请求体端点（accept/process/confirm/close）统一取值——body 缺省即无备注，
       不能让「不传请求体」落到 HttpMessageNotReadableException 变成 400 */
    private String remarkOf(WorkOrderRemarkDTO dto) {
        return dto == null ? null : dto.getRemark();
    }
}
