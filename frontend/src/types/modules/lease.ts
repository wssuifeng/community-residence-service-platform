/** C3 租住管理类型定义（接口设计.md §9.3） */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 状态枚举 ---------------------------------- */

/** 租住状态（接口设计.md 9.3.1.4 / 9.3.1.5） */
export type LeaseStatus = 'ACTIVE' | 'EXPIRED' | 'TERMINATED'

/** 租住状态中文标签 */
export const leaseStatusLabels: Record<LeaseStatus, string> = {
  ACTIVE: '租住中',
  EXPIRED: '已到期',
  TERMINATED: '已终止'
}

/** 支付方式（接口设计.md 9.3.1.1，文档示例值 MONTHLY） */
export type PaymentMethod = 'MONTHLY'

/** 支付方式中文标签 */
export const paymentMethodLabels: Record<PaymentMethod, string> = {
  MONTHLY: '按月支付'
}

/* ---------------------------------- 租住记录 ---------------------------------- */

/** 租住记录实体（接口设计.md 9.3.1.1 响应） */
export interface ILeaseRecord {
  id: number
  residentId: number
  residentName: string
  houseId: number
  houseAddress: string
  leaseStartDate: string
  leaseEndDate: string
  monthlyRent: number
  depositAmount?: number
  paymentMethod?: PaymentMethod
  contractNumber?: string
  status: LeaseStatus
  remark?: string
  createdAt: string
}

/** 创建/更新租住记录请求（接口设计.md 9.3.1.1 请求体，9.3.1.2 同） */
export interface ILeaseRecordDTO {
  residentId: number
  houseId: number
  leaseStartDate: string
  leaseEndDate: string
  monthlyRent: number
  depositAmount?: number
  paymentMethod?: PaymentMethod
  contractNumber?: string
  remark?: string
}

/** 租住记录列表查询参数（接口设计.md 9.3.1.4） */
export interface ILeaseQuery extends PageQuery {
  status?: LeaseStatus
  residentId?: number
  houseId?: number
}

/** 更新租住状态请求（接口设计.md 9.3.1.5 请求体） */
export interface IUpdateLeaseStatusDTO {
  status: LeaseStatus
  remark?: string
}

/** 即将到期租住列表查询参数（接口设计.md 9.3.1.6） */
export interface IExpiringLeaseQuery extends PageQuery {
  days?: number
}
