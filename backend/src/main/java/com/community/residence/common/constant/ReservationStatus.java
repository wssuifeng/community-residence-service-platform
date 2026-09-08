package com.community.residence.common.constant;

/** 资源预约状态常量（resource_reservation.status，六大状态机 #4：
 * 待审核→已预约→已完成 + 已拒绝/已取消/已违约） */
public final class ReservationStatus {

    public static final String PENDING = "PENDING";
    public static final String RESERVED = "RESERVED";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String VIOLATED = "VIOLATED";

    private ReservationStatus() {
    }
}
