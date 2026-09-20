package com.community.residence.common.constant;

/**
 * 工单调度标记常量（WorkOrderVO.dispatchFlag，C4 派单与调度视图）。
 * OVERDUE：待受理/待派单超 30 分钟，或已派单超 2 小时未接单，或处理中超 24 小时；
 * URGENT：优先级紧急且未完结；NEW：近 2 小时新建；NORMAL：其余。
 */
public final class DispatchFlag {

    public static final String OVERDUE = "OVERDUE";
    public static final String URGENT = "URGENT";
    public static final String NEW = "NEW";
    public static final String NORMAL = "NORMAL";

    private DispatchFlag() {
    }
}
