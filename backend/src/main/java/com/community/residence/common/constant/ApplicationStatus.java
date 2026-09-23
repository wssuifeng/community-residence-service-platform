package com.community.residence.common.constant;

/**
 * 入住申请状态常量（residence_application.status，架构设计 §6.5 + R66 v1.8 增撤回）。
 * PENDING 待审核 → APPROVED 已通过 / REJECTED 已驳回（终态）/ WITHDRAWN 已撤回（终态）。
 */
public final class ApplicationStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** 已撤回（R66）：申请人在待审核阶段主动撤回，租约与群聊均不产生 */
    public static final String WITHDRAWN = "WITHDRAWN";

    private ApplicationStatus() {
    }
}
