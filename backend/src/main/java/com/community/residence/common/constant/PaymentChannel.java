package com.community.residence.common.constant;

/**
 * 支付渠道常量（lease_payment.channel，R61 租约续约支付）。
 * 渠道凭据未配置时对应渠道禁用并明示（GET /api/v1/payment/channels）。
 */
public final class PaymentChannel {

    public static final String ALIPAY = "ALIPAY";
    public static final String WECHAT = "WECHAT";

    /** 合法渠道值（DTO 校验与分发用） */
    public static final String[] CHANNELS = {ALIPAY, WECHAT};

    private PaymentChannel() {
    }
}
