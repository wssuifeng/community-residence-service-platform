package com.community.residence.lease.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.lease.dto.CreateDepositRefundDTO;
import com.community.residence.lease.dto.CreateLeaseDTO;
import com.community.residence.lease.dto.RenewLeaseDTO;
import com.community.residence.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.lease.service.LeaseService;
import com.community.residence.lease.vo.LeaseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final com.community.residence.payment.service.PaymentService paymentService;
    private final com.community.residence.housing.service.ViewingAppointmentService viewingAppointmentService;
    private final com.community.residence.lease.service.DepositRefundService depositRefundService;

    /**
     * 发起押金退还（R72，接口设计.md §9.20.1）：社区管理员发起**原渠道原路退回**——
     * 金额可空（空 = 全额：实缴押金 − 已成功退还金额，服务端计算）、
     * `outRequestNo` 为幂等键（同号重复提交返回既有结果，同号内容不一致 5706）；
     * 门禁：租约须已搬出（或退租申请已通过，否则 5602）、押金状态须「已缴」（否则 5703）、
     * 金额 ≤ 可退押金（否则 5704）、渠道须与实缴一致（否则 5705）、渠道凭据缺失 5901；
     * 平台不托管押金资金：本端点只发起渠道退款指令并留痕。
     */
    @Operation(summary = "发起押金退还", description = "管理端对已搬出租约发起原路退回：金额可空=全额，"
            + "outRequestNo 幂等；渠道由服务端按实缴支付单推导（不接受跨渠道退款）；"
            + "失败落 FAILED 可换新请求号重试")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/deposit-refunds")
    public ApiResponse<com.community.residence.lease.vo.DepositRefundVO> createDepositRefund(
            @PathVariable Long id,
            @RequestBody @Valid CreateDepositRefundDTO dto) {
        return ApiResponse.success(depositRefundService.request(id, dto));
    }

    /** 押金退还记录（R72 §9.20.2）：居民限本人租约、管理端限绑定社区；按发起时间倒序，只读 */
    @Operation(summary = "押金退还记录", description = "该租约押金退还指令与渠道结果（含失败原因），"
            + "居民限本人租约；供「我的租约」押金明细与租约明细时间线")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/deposit-refunds")
    public ApiResponse<java.util.List<com.community.residence.lease.vo.DepositRefundVO>> depositRefunds(
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
    public ApiResponse<java.util.List<com.community.residence.housing.vo.ViewingAppointmentVO>> viewings(
            @PathVariable Long id) {
        return ApiResponse.success(viewingAppointmentService.listByLease(id));
    }

    /** 该租约支付单列表（R71）：含 paymentType（FIRST 首期 / RENEWAL 续期），居民限本人租约 */
    @Operation(summary = "租约支付单列表", description = "含支付单类型标签，供「我的租约」支付记录与租约详情展示")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/payments")
    public ApiResponse<java.util.List<com.community.residence.payment.vo.LeasePaymentVO>> payments(
            @PathVariable Long id) {
        return ApiResponse.success(paymentService.listByLease(id));
    }

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
    public ApiResponse<com.community.residence.payment.vo.LeasePaymentVO> createFirstPayment(
            @PathVariable Long id,
            @RequestBody @Valid com.community.residence.payment.dto.CreateFirstPaymentDTO dto) {
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
    public ApiResponse<com.community.residence.payment.vo.LeasePaymentVO> createRenewPayment(
            @PathVariable Long id,
            @RequestBody @Valid com.community.residence.payment.dto.CreateRenewPaymentDTO dto) {
        return ApiResponse.success(paymentService.createRenewPayment(id, dto));
    }

    @Operation(summary = "创建租住记录", description = "管理员登记，初始待审核")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<LeaseVO> create(@RequestBody @Valid CreateLeaseDTO dto) {
        return ApiResponse.success(leaseService.create(dto));
    }

    @Operation(summary = "更新租住记录")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<LeaseVO> update(@PathVariable Long id, @RequestBody @Valid CreateLeaseDTO dto) {
        return ApiResponse.success(leaseService.update(id, dto));
    }

    @Operation(summary = "续租", description = "仅已生效租约；止期顺延，租金/押金更新，状态保持已生效")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/renew")
    public ApiResponse<LeaseVO> renew(@PathVariable Long id, @RequestBody @Valid RenewLeaseDTO dto) {
        throw new com.community.residence.common.exception.BusinessException(com.community.residence.common.constant.ErrorCode.INVALID_PARAM,"请由租客提交租期延长申请，经管理方审核；此旧续租拟约入口已停用");
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

    @Operation(summary = "更新租住状态",
            description = "状态机流转校验：待审核→待签约/已驳回，待签约→已驳回；"
                    + "生效仅由协议完成后的首期费用成功驱动，不提供管理员直接生效")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateLeaseStatusDTO dto) {
        leaseService.updateStatus(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "租约变更历史", description = "字段级前后值留痕：登记/属性编辑/续租/状态流转/协议签约")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/changes")
    public ApiResponse<java.util.List<com.community.residence.lease.vo.LeaseChangeVO>> changes(
            @PathVariable Long id) {
        return ApiResponse.success(leaseService.changes(id));
    }

    @Operation(summary = "即将到期租住列表", description = "按到期日期升序")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/expiring")
    public ApiResponse<PageVO<LeaseVO>> expiring(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(leaseService.expiring(page, size, days));
    }
    @Operation(summary="合同期内普通交租，不延长合同")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/rent-payments")
    public ApiResponse<com.community.residence.payment.vo.LeasePaymentVO> rentPayment(@PathVariable Long id,@RequestBody @Valid com.community.residence.payment.dto.CreateRenewPaymentDTO dto){return ApiResponse.success(paymentService.createRentPayment(id,dto));}
    @Operation(summary="读取合同段服务端报价，不创建支付或访问渠道")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/{id}/rent-quote")
    public ApiResponse<com.community.residence.payment.vo.LeasePaymentVO> quote(@PathVariable Long id,@RequestParam(defaultValue="RENT")String purpose,@RequestParam(required=false)Integer months){
        if(!java.util.Set.of("FIRST","RENT","RENEWAL").contains(purpose))throw new com.community.residence.common.exception.BusinessException(com.community.residence.common.constant.ErrorCode.INVALID_PARAM,"付款用途无效");
        return ApiResponse.success(paymentService.quote(id,purpose,months));}
}
