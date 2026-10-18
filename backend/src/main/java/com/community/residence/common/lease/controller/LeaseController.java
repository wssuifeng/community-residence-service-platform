package com.community.residence.common.lease.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.lease.vo.LeaseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租住管理控制器（接口设计.md 9.3.1） */
@Tag(name = "租住管理", description = "租住记录与状态流转接口")
@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;
    private final com.community.residence.common.payment.service.PaymentService paymentService;
    private final com.community.residence.common.housing.service.ViewingAppointmentService viewingAppointmentService;
    private final com.community.residence.common.lease.service.DepositRefundService depositRefundService;

    /** 押金退还记录（R72 §9.20.2）：居民限本人租约、管理端限绑定社区；按发起时间倒序，只读 */
    @Operation(summary = "押金退还记录", description = "该租约押金退还指令与渠道结果（含失败原因），"
            + "居民限本人租约；供「我的租约」押金明细与租约明细时间线")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/deposit-refunds")
    public ApiResponse<java.util.List<com.community.residence.common.lease.vo.DepositRefundVO>> depositRefunds(
            @PathVariable Long id) {
        return ApiResponse.success(depositRefundService.listByLease(id));
    }

    /**
     * 承租确认（R67，接口设计.md 9.19）：仅租约本人、仅待签约状态；
     * 幂等且不覆盖首次留痕（tenant_confirm_at/by 只写一次），确认后通知本社区管理方。
     */
    @Operation(summary = "租约关联看房预约列表", description = "会话内发起的看房预约按 lease_id 归属，居民限本人租约")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/viewings")
    public ApiResponse<java.util.List<com.community.residence.common.housing.vo.ViewingAppointmentVO>> viewings(
            @PathVariable Long id) {
        return ApiResponse.success(viewingAppointmentService.listByLease(id));
    }

    /** 该租约支付单列表（R71）：含 paymentType（FIRST 首期 / RENEWAL 续期），居民限本人租约 */
    @Operation(summary = "租约支付单列表", description = "含支付单类型标签，供「我的租约」支付记录与租约详情展示")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/payments")
    public ApiResponse<java.util.List<com.community.residence.common.payment.vo.LeasePaymentVO>> payments(
            @PathVariable Long id) {
        return ApiResponse.success(paymentService.listByLease(id));
    }

    @Operation(summary = "查询租住详情", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<LeaseVO> getById(@PathVariable Long id) {
        return ApiResponse.success(leaseService.getById(id));
    }

    @Operation(summary = "租住记录列表（分页）", description = "居民只看本人记录")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<LeaseVO>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long residentId,
                                             @RequestParam(required = false) Long houseId) {
        return ApiResponse.success(leaseService.page(page, size, status, residentId, houseId));
    }

    @Operation(summary = "租约变更历史", description = "字段级前后值留痕：登记/属性编辑/续租/状态流转/协议签约")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/changes")
    public ApiResponse<java.util.List<com.community.residence.common.lease.vo.LeaseChangeVO>> changes(
            @PathVariable Long id) {
        return ApiResponse.success(leaseService.changes(id));
    }
}
