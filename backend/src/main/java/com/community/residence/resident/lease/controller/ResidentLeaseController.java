package com.community.residence.resident.lease.controller;

import com.community.residence.common.web.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租住管理控制器（接口设计.md 9.3.1） */
@Tag(name = "租住管理", description = "租住记录与状态流转接口")
@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class ResidentLeaseController {
    private final com.community.residence.common.payment.service.PaymentService paymentService;

    /**
     * 发起首期费用支付（R71，接口设计.md 9.19.12）：仅本人已生效租约且**未到期**
     * （止期严格早于当天即拒 LEASE_EXPIRED(5606)，DEF-069 服务端兜底；当天到期不算已到期）；
     * payment_type=FIRST、months=1、金额 = 月租 + 押金（服务端计算）；
     * 幂等（DEF-068）：已存在进行中（PENDING）首期单则原样返回该单并重取渠道凭据，
     * 仅已完成（SUCCESS）时拒 PAYMENT_ALREADY_EXISTS(5904)，已关闭（CLOSED）允许重新发起；
     * 支付成功**不顺延** end_date（首期与租约生效解耦）。
     */
    @Operation(summary = "发起首期费用支付", description = "首月租金 + 押金，金额服务端计算；"
            + "已到期租约拒绝 5606（续期支付不受限）；已存在进行中首期单则幂等返回该单"
            + "（重取渠道凭据），仅已完成时拒绝 5904")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/first-payments")
    public ApiResponse<com.community.residence.common.payment.vo.LeasePaymentVO> createFirstPayment(
            @PathVariable Long id,
            @RequestBody @Valid com.community.residence.common.payment.dto.CreateFirstPaymentDTO dto) {
        return ApiResponse.success(paymentService.createFirstPayment(id, dto));
    }

    /**
     * 发起租约续约支付（R61，接口设计.md 9.13.2）：仅本人已生效租约；
     * 金额服务端按月租×月数计算；同租约已有待支付续期单时幂等返回该单；
     * 支付宝返回跳转表单，微信返回二维码链接。
     */
    @Operation(summary = "发起租约续约支付", description = "居民端在线续租：选月数与渠道创建支付单，"
            + "金额服务端计算防篡改；待支付单幂等返回")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/renew-payments")
    public ApiResponse<com.community.residence.common.payment.vo.LeasePaymentVO> createRenewPayment(
            @PathVariable Long id,
            @RequestBody @Valid com.community.residence.common.payment.dto.CreateRenewPaymentDTO dto) {
        return ApiResponse.success(paymentService.createRenewPayment(id, dto));
    }
    @Operation(summary="合同期内普通交租，不延长合同")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/rent-payments")
    public ApiResponse<com.community.residence.common.payment.vo.LeasePaymentVO> rentPayment(@PathVariable Long id,@RequestBody @Valid com.community.residence.common.payment.dto.CreateRenewPaymentDTO dto){return ApiResponse.success(paymentService.createRentPayment(id,dto));}
    @Operation(summary="读取合同段服务端报价，不创建支付或访问渠道")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/{id}/rent-quote")
    public ApiResponse<com.community.residence.common.payment.vo.LeasePaymentVO> quote(@PathVariable Long id,@RequestParam(defaultValue="RENT")String purpose,@RequestParam(required=false)Integer months){
        if(!java.util.Set.of("FIRST","RENT","RENEWAL").contains(purpose))throw new com.community.residence.common.web.exception.BusinessException(com.community.residence.common.web.constant.ErrorCode.INVALID_PARAM,"付款用途无效");
        return ApiResponse.success(paymentService.quote(id,purpose,months));}
}
