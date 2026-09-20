package com.community.residence.payment.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.payment.dto.CreateRenewPaymentDTO;
import com.community.residence.payment.service.PaymentService;
import com.community.residence.payment.vo.LeasePaymentVO;
import com.community.residence.payment.vo.PaymentChannelVO;
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

/**
 * 支付管理控制器（接口设计.md 9.13，R61 租约续约支付）。
 * 管理端查续约与支付记录复用居民端查询端点：GET /payment/orders/{no}
 * 支持本人或管理员（Service 层读权限校验）。
 * 发起支付端点挂在租约资源路径下（契约口径 POST /api/v1/leases/{leaseId}/renew-payments），
 * 以绝对路径映射避免与 LeaseController 的 /api/v1/leases 基础路径耦合。
 *
 * -----------------------------------------------------------------------------
 * 【公网 notify 回调接入点预留（决策日志 2026-09-20：本地以主动查单替代回调，
 * 公网部署时按官方文档补如下控制器方法即可，本地不实现）】
 *
 * 支付宝（POST /api/v1/payment/notify/alipay，form 参数）：
 *   1. 用支付宝公钥对 params 全量验签（SDK：AlipaySignature.rsaCheckV1）；
 *   2. 校验 out_trade_no 对应支付单存在且金额一致（total_amount 与单据 amount 比对）；
 *   3. trade_status ∈ {TRADE_SUCCESS, TRADE_FINISHED} → 落账（复用
 *      PaymentService.settleSuccess 口径）；app_id 须与本方配置一致防冒单；
 *   4. 处理成功返回纯文本 "success"，其余返回 "failure"（支付宝按官方重试策略补推）。
 *
 * 微信（POST /api/v1/payment/notify/wechat，JSON 报文）：
 *   1. 从 Wechatpay-* 请求头用平台证书验签（SDK：NotificationParser.parse）；
 *   2. 以 APIv3 密钥 AES-GCM 解密 resource 得到 Transaction 报文；
 *   3. event_type=TRANSACTION.SUCCESS → 校验 out_trade_no 与 amount.total（分）
 *      后落账（同 settleSuccess 口径）；
 *   4. 返回 HTTP 200 + {"code":"SUCCESS"}，失败返回 4xx/5xx 触发官方重试。
 *
 * 落账幂等由 settleSuccess 的状态重入保护保证（仅 PENDING 单可落账），
 * 回调与主动查单并存时不会重复延展租期。
 * -----------------------------------------------------------------------------
 */
@Tag(name = "支付管理", description = "租约续约支付：渠道查询、支付单创建/查询/关闭")
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "可用支付渠道", description = "凭据未配置的渠道 enabled=false；居民端按需明示禁用")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/channels")
    public ApiResponse<List<PaymentChannelVO>> channels() {
        return ApiResponse.success(paymentService.channels());
    }

    /* 发起支付端点归 LeaseController（/api/v1/leases/{leaseId}/renew-payments，
       类级基础路径不同，挂此处会拼接出错路径——契约见接口设计.md 9.13.2） */

    @Operation(summary = "查询支付单", description = "本人或管理员；待支付单会主动向渠道查单，"
            + "查到已支付自动落账并延展租期")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/orders/{paymentNo}")
    public ApiResponse<LeasePaymentVO> getStatus(@PathVariable String paymentNo) {
        return ApiResponse.success(paymentService.getStatus(paymentNo));
    }

    @Operation(summary = "关闭支付单", description = "居民放弃支付；仅待支付状态可关")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/orders/{paymentNo}/close")
    public ApiResponse<Void> closePayment(@PathVariable String paymentNo) {
        paymentService.closePayment(paymentNo);
        return ApiResponse.success();
    }

    @Operation(summary = "本人支付单列表（分页）", description = "居民视角，可选渠道过滤")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/orders")
    public ApiResponse<PageVO<LeasePaymentVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String channel) {
        return ApiResponse.success(paymentService.page(page, size, channel));
    }
}
