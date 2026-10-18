package com.community.residence.common.lease.constant;

/**
 * 押金状态常量（lease_record.deposit_status，R72 押金状态与在线退还）。
 * 流转：UNPAID（默认）→（首期费用支付成功，押金部分入账）→ PAID →（管理端发起原路退还）
 * → REFUNDING →（渠道退款成功）→ REFUNDED；退款失败由 REFUNDING 回落 PAID（可重试）；
 * 结算抵扣置 OFFSET（处置金额与说明在该租约的 lease_deposit_refund 留痕）。
 * 边界：本状态只表达「实缴与退还进度」，不代表平台持有余额（不托管、不计息、不垫付）。
 */
public final class DepositStatus {

    public static final String UNPAID = "UNPAID";
    public static final String PAID = "PAID";
    public static final String REFUNDING = "REFUNDING";
    public static final String REFUNDED = "REFUNDED";
    public static final String OFFSET = "OFFSET";

    private DepositStatus() {
    }
}
