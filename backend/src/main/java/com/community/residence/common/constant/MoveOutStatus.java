package com.community.residence.common.constant;

/**
 * 退租申请状态常量（lease_move_out_application.status，R70 居民自助退租）。
 * PENDING 待审核 → APPROVED 已通过（租约流转已搬出）/ REJECTED 已驳回（租约保持已生效）。
 */
public final class MoveOutStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private MoveOutStatus() {
    }
}
