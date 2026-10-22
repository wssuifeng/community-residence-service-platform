package com.community.residence.admin.lease.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.lease.dto.CreateDepositRefundDTO;
import com.community.residence.common.lease.dto.CreateLeaseDTO;
import com.community.residence.common.lease.dto.RenewLeaseDTO;
import com.community.residence.common.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.lease.vo.LeaseVO;
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
public class AdminLeaseController {

    private final LeaseService leaseService;
    private final com.community.residence.common.lease.service.DepositRefundService depositRefundService;

    /**
     * 发起押金退还（R72，接口设计.md §9.20.1）：小区管理员发起**原渠道原路退回**——
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
    public ApiResponse<com.community.residence.common.lease.vo.DepositRefundVO> createDepositRefund(
            @PathVariable Long id,
            @RequestBody @Valid CreateDepositRefundDTO dto) {
        return ApiResponse.success(depositRefundService.request(id, dto));
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
        throw new com.community.residence.common.web.exception.BusinessException(com.community.residence.common.web.constant.ErrorCode.INVALID_PARAM,"请由租客提交租期延长申请，经管理方审核；此旧续租拟约入口已停用");
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

    @Operation(summary = "即将到期租住列表", description = "按到期日期升序")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/expiring")
    public ApiResponse<PageVO<LeaseVO>> expiring(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(leaseService.expiring(page, size, days));
    }
}
