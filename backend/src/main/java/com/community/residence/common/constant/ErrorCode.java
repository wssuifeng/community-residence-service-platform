package com.community.residence.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码定义（接口设计.md §3：HTTP 层 400/401/403/404/500，
 * 业务错误码按模块分段 5001~5999）。
 * 业务异常统一返回 HTTP 200 + 5xxx 业务码，由 GlobalExceptionHandler 映射。
 *
 * 标准错误码规范（2026-09-23 制定，详见 接口设计.md §3.3）：
 * ① 按「段」划分（4000~4999 通用请求错误 / 5xxx 各业务域），段内连续；
 * ② HTTP 语义码段（4xxx）的取值**等于**对应 HTTP 状态码，响应体仍为统一 ApiResponse；
 * ③ 框架级请求错误（方法不支持 / 媒体类型不支持 / 请求体不可读 / 路由不存在）一律
 *    按上述 4xxx 码映射到**语义正确的 HTTP 状态码**，禁止落入 500 兜底；
 * ④ 只增不改：已发布码不得改号、不得改语义。
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

    /* ---- 通用请求错误段 4000~4099（2026-09-23 制定） ----
       承载**框架级请求错误**：Spring MVC / Security 在进入业务代码前即判定失败的请求。
       这些错误必须返回**语义正确的 HTTP 状态码**（405 就是 405、415 就是 415），
       响应体仍为统一 ApiResponse；**禁止**再落入 500 兜底（DEF-099）。
       段内取值规则：语义与某 HTTP 状态码一一对应时**取值等于该状态码**（400/405/415）；
       无对应状态码可用者取段内**首个空号**（本段首个 404 语义取 4001）。
       4002~4099 为后续通用请求错误预留（当前未占用）。 */
    REQUEST_METHOD_NOT_ALLOWED(405, "请求方法不被支持"),
    REQUEST_MEDIA_TYPE_UNSUPPORTED(415, "请求内容类型不被支持"),
    REQUEST_BODY_MALFORMED(400, "请求体缺失或格式不正确，请提交合法的 JSON 请求体"),
    /* 4001 语义 = 404 路由不存在（HTTP 404 已被 NOT_FOUND 占用，故取段内首个空号） */
    REQUEST_PATH_NOT_FOUND(4001, "接口不存在"),

    /* ---- 通用业务错误 5001~5099 ---- */
    OPERATION_FAILED(5001, "操作失败"),
    DATA_EXISTS(5002, "数据已存在"),
    DATA_NOT_FOUND(5003, "数据不存在"),
    STATE_TRANSITION_INVALID(5004, "状态流转非法"),

    /* ---- 社区管理 5101~5199 ---- */
    COMMUNITY_INACTIVE(5101, "社区已停用"),
    BUILDING_REFERENCED(5102, "楼栋被引用，无法删除"),
    UNIT_REFERENCED(5103, "单元下存在房屋，无法删除"),
    HOUSE_HAS_RESIDENT(5104, "房屋存在在住居民，无法删除"),
    RESOURCE_HAS_RESERVATION(5105, "资源存在未完成预约，无法删除"),
    TIMESLOT_HAS_RESERVATION(5106, "时段存在预约，无法删除"),
    TIMESLOT_CONFLICT(5107, "时段与已有时段重叠"),

    /* ---- 居民管理 5201~5299 ---- */
    ACCOUNT_FROZEN(5201, "账号已冻结"),
    APPLICATION_ALREADY_REVIEWED(5202, "入住申请已审核"),
    OLD_PASSWORD_MISMATCH(5203, "旧密码不正确"),
    REGISTRATION_DISABLED(5204, "居民注册未开放"),
    HOUSE_NOT_VACANT(5205, "房屋当前不可申请入住"),
    APPLICATION_PENDING_EXISTS(5206, "已有待审核的入住申请"),
    RELATION_NOT_ACTIVE(5207, "居住关系不在住，不可办理迁出"),

    /* ---- 工单管理 5301~5399 ---- */
    WORK_ORDER_NOT_FOUND(5301, "工单不存在"),
    WORK_ORDER_INVALID_TRANSITION(5302, "工单状态流转非法"),

    /* ---- 预约管理 5401~5499 ---- */
    RESERVATION_CONFLICT(5401, "预约时段冲突"),
    RESERVATION_CANCELLED(5402, "预约已取消"),
    RESERVATION_PAST_SLOT(5403, "不能预约已过去的日期或时段"),
    RESERVATION_DATE_LIMIT(5404, "最多可提前 7 天预约"),
    RESERVATION_DURATION_LIMIT(5405, "单次预约超过连续时长上限"),
    /* DEF-062：同账号同日跨资源/跨域时间重叠（预约人日程冲突，「影分身」拦截） */
    RESERVATION_SCHEDULE_CONFLICT(5406, "该时间段已有本人的其他预约安排"),

    /* ---- 评价管理 5501~5599 ---- */
    EVALUATION_NOT_ALLOWED(5501, "工单未完成，不可评价"),
    EVALUATION_DUPLICATE(5502, "已评价，不可重复评价"),

    /* ---- 租住签约闭环 5601~5699（R66~R71 v1.8） ---- */
    LEASE_TENANCY_NOT_CONFIRMED(5601, "承租尚未确认，不可发起协议"),
    LEASE_STATUS_INVALID(5602, "当前租约状态不允许该操作"),
    MOVE_OUT_APPLICATION_PENDING(5603, "已有待审核的退租申请"),
    CONVERSATION_CLOSED(5604, "会话已关闭，不可再发送消息"),
    APPLICATION_NOT_WITHDRAWABLE(5605, "当前申请状态不可撤回"),
    /* DEF-069：已到期（止期严格早于当天）租约不得再支付首期费用；续期支付不受限（到期正是续期场景） */
    LEASE_EXPIRED(5606, "租约已到期，请续租或办理退租"),
    /* 保留号段（2026-09-22 用户裁决）：本批曾用 5607/5608 作承约前置拒绝理由，裁决
       「看房预约与租约群聊是沟通手段、不是承约门槛」后**不再作为任何拒绝理由**；
       号段定义保留以免制造编号空洞，文案改为中性描述，语义留给后续真正需要该前置的场景复用 */
    LEASE_TENANCY_VIEWING_REQUIRED(5607, "尚未完成本房源看房预约"),
    LEASE_TENANCY_CONVERSATION_REQUIRED(5608, "租约沟通会话未建立"),

    /* ---- 权限管理 5701~5702（C10，既有契约不得改号） ---- */
    SELF_FREEZE_FORBIDDEN(5701, "不可冻结自己的账号"),
    BIND_ROLE_INVALID(5702, "仅社区管理员可绑定社区"),

    /* ---- 押金退还 5703~5706（R72） ----
       段位裁决（2026-09-22，用户口径）：接口设计 v1.5 §3.2 原把 5701~5799 整段划给押金退还，
       但 5701/5702 是 C10 已生效的既有契约（SELF_FREEZE_FORBIDDEN / BIND_ROLE_INVALID），
       **新旧冲突时新功能取空号、老码不搬家**——押金退还四码因此落在本段首个连续空块
       5703~5706（5700~5799 其余号段未被占用，见同名守卫），语义顺序与 §9.20.1 一致：
       状态不允许 / 超实缴 / 渠道不一致 / 请求号冲突。 */
    DEPOSIT_STATUS_NOT_REFUNDABLE(5703, "押金状态不允许退还"),
    DEPOSIT_AMOUNT_EXCEEDED(5704, "退款金额超过实缴押金"),
    DEPOSIT_CHANNEL_MISMATCH(5705, "退款渠道与实缴渠道不一致"),
    DEPOSIT_OUT_REQUEST_CONFLICT(5706, "退款请求号冲突（同号内容不一致）"),

    /* ---- 看房预约 5801~5899 ---- */
    PAST_SLOT(5804, "不能预约已过去的时段"),
    VIEWING_ASSIGNEE_INVALID(5805, "目标账号不可分配"),
    VIEWING_DURATION_LIMIT(5806, "单次看房预约超过连续时长上限"),
    /* DEF-062：同账号同日跨房源/跨域时间重叠（预约人日程冲突，「影分身」拦截） */
    VIEWING_SCHEDULE_CONFLICT(5807, "该时间段已有本人的其他预约安排"),

    /* ---- 支付管理 5901~5999（R61 租约续约支付） ---- */
    PAYMENT_CHANNEL_DISABLED(5901, "该支付渠道未配置"),
    PAYMENT_CREATE_FAILED(5902, "发起支付失败，请稍后重试"),
    PAYMENT_AMOUNT_MISMATCH(5903, "渠道支付金额与应付金额不一致"),
    /* R71 首期支付（DEF-068 口径）：同租约同类型首期单**已完成 SUCCESS** 时触发；
       进行中 PENDING 走幂等续接（原样返回该单并重取凭据）、已关闭 CLOSED 允许重新发起，
       均不再触发本码（文案沿用接口设计.md §9.19.15 表，未单方面收窄） */
    PAYMENT_ALREADY_EXISTS(5904, "该类型支付单已完成或进行中");

    private final int code;
    private final String message;
}
