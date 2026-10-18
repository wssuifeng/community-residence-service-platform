package com.community.residence.common.payment.constant;

/**
 * 押金退还单状态常量（lease_deposit_refund.status，R72）。
 * PENDING 处理中 → SUCCESS 已退还 / FAILED 失败（FAILED 必填 fail_reason，
 * 重试须换新的 out_request_no 再发起，历史记录追加留痕不覆盖）。
 */
public final class DepositRefundStatus {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private DepositRefundStatus() {
    }
}
