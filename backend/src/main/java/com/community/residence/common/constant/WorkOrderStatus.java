package com.community.residence.common.constant;

/**
 * 工单状态常量（work_order.status，六大状态机 #1，
 * 权威定义架构设计.md §6：待受理→待派单→已派单→已接单→处理中→待确认→已完成
 * + 已关闭/已驳回/已取消）。
 */
public final class WorkOrderStatus {

    public static final String PENDING = "PENDING";
    public static final String TO_ASSIGN = "TO_ASSIGN";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String TO_CONFIRM = "TO_CONFIRM";
    public static final String COMPLETED = "COMPLETED";
    public static final String CLOSED = "CLOSED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    private WorkOrderStatus() {
    }
}
