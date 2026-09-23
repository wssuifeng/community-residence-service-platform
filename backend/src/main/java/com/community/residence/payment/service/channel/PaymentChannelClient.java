package com.community.residence.payment.service.channel;

import com.community.residence.payment.entity.LeasePayment;

import java.time.LocalDateTime;

/**
 * 支付渠道客户端抽象：凭据门控（enabled）+ 发起支付 + 主动查单。
 * 本地环境无公网回调，支付结果以主动查单为准（决策日志 2026-09-20 R61）。
 */
public interface PaymentChannelClient {

    /** 渠道标识：ALIPAY / WECHAT */
    String channel();

    /** 渠道是否启用（凭据齐全才启用，禁用不报错不阻断） */
    boolean enabled();

    /**
     * 发起支付：向渠道下单，返回支付凭据。
     * ALIPAY → 跳转表单 HTML（pageExecute，前端写入页面自动提交）；
     * WECHAT → Native code_url（前端生成二维码扫码）。
     */
    String createPayment(LeasePayment payment, String subject, String notifyUrl);

    /**
     * 主动查单：查询渠道侧支付状态。
     *
     * @return 支付成功时的渠道信息（流水号 + 完成时间）；未支付/已关闭返回 null
     */
    ChannelTradeResult queryTrade(String paymentNo);

    /**
     * 发起退款（R72 押金原路退还）：按原支付单向渠道提交退款指令。
     * 渠道侧同 outRequestNo 重复提交返回同一结果（幂等由渠道保证）；
     * 渠道业务拒绝返回 success=false + failReason（不抛异常），
     * 网络/凭据异常抛 ChannelCallException 由调用方落 FAILED 留痕。
     *
     * @param payment      原支付单（渠道以 paymentNo 定位原交易）
     * @param outRequestNo 商户退款请求号（幂等键）
     * @param amount       本次退款金额
     * @param reason       退款说明（渠道账单可见）
     */
    ChannelRefundResult refund(LeasePayment payment, String outRequestNo,
                               java.math.BigDecimal amount, String reason);
}
