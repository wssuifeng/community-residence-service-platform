package com.community.residence.common.constant;

/**
 * 支付单类型常量（lease_payment.payment_type，R71 首期 / R61 续期同表不同型）。
 * FIRST 金额 = 首月租金 + 押金（月数固定 1，与租约生效解耦）；
 * RENEWAL 金额 = 月租 × 月数（支付成功后顺延租期止期）。
 */
public final class PaymentType {

    public static final String FIRST = "FIRST";
    public static final String RENEWAL = "RENEWAL";

    private PaymentType() {
    }
}
