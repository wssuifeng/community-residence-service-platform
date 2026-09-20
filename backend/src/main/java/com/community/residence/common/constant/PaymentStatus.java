package com.community.residence.common.constant;

/**
 * 支付单状态常量（lease_payment.status，R61 租约续约支付）。
 * 状态机：PENDING → SUCCESS / CLOSED，SUCCESS 与 CLOSED 为终态。
 */
public final class PaymentStatus {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String CLOSED = "CLOSED";

    private PaymentStatus() {
    }
}
