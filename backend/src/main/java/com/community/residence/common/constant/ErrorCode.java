package com.community.residence.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码定义（接口设计.md §3：HTTP 层 400/401/403/404/500，
 * 业务错误码按模块分段 5001~5599）。
 * 业务异常统一返回 HTTP 200 + 5xxx 业务码，由 GlobalExceptionHandler 映射。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    /* ---- HTTP 层（与 HTTP 状态码一致） ---- */
    INVALID_PARAM(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未认证或令牌已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    DUPLICATE_RESOURCE(409, "数据已存在"),
    INTERNAL_ERROR(500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, "系统繁忙，请稍后重试"),

    /* ---- 通用业务错误 5001~5099 ---- */
    OPERATION_FAILED(5001, "操作失败"),
    DATA_EXISTS(5002, "数据已存在"),
    DATA_NOT_FOUND(5003, "数据不存在"),

    /* ---- 社区管理 5101~5199 ---- */
    COMMUNITY_INACTIVE(5101, "社区已停用"),
    BUILDING_REFERENCED(5102, "楼栋被引用，无法删除"),

    /* ---- 居民管理 5201~5299 ---- */
    ACCOUNT_FROZEN(5201, "账号已冻结"),
    APPLICATION_ALREADY_REVIEWED(5202, "入住申请已审核"),

    /* ---- 工单管理 5301~5399 ---- */
    WORK_ORDER_NOT_FOUND(5301, "工单不存在"),
    WORK_ORDER_INVALID_TRANSITION(5302, "工单状态流转非法"),

    /* ---- 预约管理 5401~5499 ---- */
    RESERVATION_CONFLICT(5401, "预约时段冲突"),
    RESERVATION_CANCELLED(5402, "预约已取消"),

    /* ---- 评价管理 5501~5599 ---- */
    EVALUATION_NOT_ALLOWED(5501, "工单未完成，不可评价"),
    EVALUATION_DUPLICATE(5502, "已评价，不可重复评价");

    private final int code;
    private final String message;
}
