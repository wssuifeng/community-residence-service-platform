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
}
