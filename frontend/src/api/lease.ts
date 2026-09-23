/** C3 租住管理接口（对齐后端 LeaseController 实测契约） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { IViewingAppointment } from '@/types/modules/housing'
import type { ConversationViewingCreateDTO } from '@/types/modules/conversation'
import type {
  ICreateDepositRefundDTO,
  IDepositRefund,
  IExpiringLeaseQuery,
  ILeaseChange,
  ILeasePayment,
  ILeaseQuery,
  ILeaseRecord,
  ILeaseRecordDTO,
  IRenewLeaseDTO,
  IUpdateLeaseStatusDTO
} from '@/types/modules/lease'

/** 创建租住记录（管理员登记，初始待审核） */
export function createLease(data: ILeaseRecordDTO) {
  return http.post<ILeaseRecord>('/leases', data)
}

/** 更新租住记录（不允许变更房屋） */
export function updateLease(id: number, data: ILeaseRecordDTO) {
  return http.put<ILeaseRecord>(`/leases/${id}`, data)
}

/** 续租（需求 E3：仅已生效租约，止期顺延、租金/押金更新） */
export function renewLease(id: number, data: IRenewLeaseDTO) {
  return http.post<ILeaseRecord>(`/leases/${id}/renew`, data)
}

/** 查询租住详情 */
export function getLease(id: number) {
  return http.get<ILeaseRecord>(`/leases/${id}`)
}

/** 租住记录分页列表 */
export function getLeaseList(params: ILeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases', params)
}

/** 更新租住状态（状态机流转校验） */
export function updateLeaseStatus(id: number, data: IUpdateLeaseStatusDTO) {
  return http.patch<null>(`/leases/${id}/status`, data)
}

/** 即将到期租住分页列表（按到期日期升序） */
export function getExpiringLeaseList(params?: IExpiringLeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases/expiring', params)
}

/** 租约变更历史（V18 字段级前后值留痕，倒序；居民限本人租约） */
export function getLeaseChanges(id: number) {
  return http.get<ILeaseChange[]>(`/leases/${id}/changes`)
}

/* ------------------------- R67 承租确认（V20 租住签约闭环） ------------------------- */

/**
 * 承租确认（R67）：居民本人对「待签约」租约确认承租，记录确认人与时间。
 * 幂等且不覆盖首次留痕；确认后管理方方可发起租赁协议。
 */
export function confirmTenancy(id: number) {
  return http.post<ILeaseRecord>(`/leases/${id}/tenancy-confirm`)
}

/* ------------------------- R69 租约关联看房预约（V20） ------------------------- */

/** 该租约关联的看房预约列表（R69 双向关联；居民限本人租约） */
export function listLeaseViewings(id: number) {
  return http.get<IViewingAppointment[]>(`/leases/${id}/viewings`)
}

/**
 * 会话内发起看房预约（R69/V20：POST /conversations/{id}/viewing-appointments）。
 * 契约要点：复用看房预约同表、同状态机、同冲突校验；所属租约由会话 relatedId
 * 推导（请求体不带 leaseId），且**不再另建看房群聊**——预约沟通回到该租约群。
 */
export function createConversationViewing(
  conversationId: number,
  data: ConversationViewingCreateDTO
) {
  return http.post<IViewingAppointment>(`/conversations/${conversationId}/viewing-appointments`, data)
}

/** 该租约的支付单列表（V20；含 paymentType，供「我的租约」支付记录与租约详情） */
export function listLeasePayments(id: number) {
  return http.get<ILeasePayment[]>(`/leases/${id}/payments`)
}

/* ------------------------- R72 押金退还（V22 押金状态与在线退还） ------------------------- */

/**
 * 发起押金退还（R72，接口设计 §9.20.1）：**管理端专属**（ADMIN 限绑定社区 / SUPER_ADMIN），
 * 居民不可发起——故调用方只有管理端页面与其押金退还弹窗。
 *
 * 契约要点（前端不得替代服务端判定，只做前置提示）：
 * - 渠道**不接受客户端指定**：服务端取该租约实缴支付单的渠道原路退回，请求体无 channel；
 * - `amount` 空 = 全额退还（实缴押金 − 已成功退还金额），金额由服务端计算；
 * - `outRequestNo` 为幂等键：同号重复提交返回既有记录（5706 = 同号但内容不一致）；
 * - 凭据门控：退款渠道凭据未配置时返回 5901，界面需明示原因而非静默失败。
 * - 错误码（后端定稿 2026-09-22，5701/5702 属 C10 权限码，押金段为 5703~5706）：
 *   5703 押金状态不允许退还 / 5704 退款金额超过实缴押金 / 5705 渠道与实缴渠道不一致 /
 *   5706 退款请求号冲突。
 */
export function createDepositRefund(id: number, data: ICreateDepositRefundDTO) {
  return http.post<IDepositRefund>(`/leases/${id}/deposit-refunds`, data)
}

/** 押金退还记录（R72 §9.20.2）：居民限本人租约 / 管理端限绑定社区，按发起时间降序、不分页 */
export function listDepositRefunds(id: number) {
  return http.get<IDepositRefund[]>(`/leases/${id}/deposit-refunds`)
}
