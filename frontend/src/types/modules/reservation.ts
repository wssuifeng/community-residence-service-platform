import type { PageQuery } from '@/types/api'

/** 资源预约状态（接口设计.md 9.7.1，状态机：待审核→已预约→已完成 + 已拒绝/已取消/已违约） */
export type ReservationStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'VIOLATED'

/** 资源预约状态中文标签 */
export const reservationStatusLabels: Record<ReservationStatus, string> = {
  PENDING: '待审核',
  CONFIRMED: '已预约',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
  VIOLATED: '已违约'
}

/** 可预约时段状态（接口设计.md 9.7.1.9 响应示例） */
export type ResourceTimeslotStatus = 'AVAILABLE' | 'FULL'

/** 可预约时段状态中文标签 */
export const resourceTimeslotStatusLabels: Record<ResourceTimeslotStatus, string> = {
  AVAILABLE: '可预约',
  FULL: '已约满'
}

/** 违约类型（接口设计.md 9.7.2.1：资源预约/看房预约） */
export type ViolationType = 'RESOURCE_RESERVATION' | 'VIEWING_APPOINTMENT'

/** 违约类型中文标签 */
export const violationTypeLabels: Record<ViolationType, string> = {
  RESOURCE_RESERVATION: '资源预约',
  VIEWING_APPOINTMENT: '看房预约'
}

/** 资源预约单（接口设计.md 9.7.1.1 / 9.7.1.2 响应 data） */
export interface IResourceReservation {
  id: number
  reservationNumber: string
  residentId: number
  residentName: string
  resourceId: number
  resourceName: string
  timeslotId: number
  reservationDate: string
  startTime: string
  endTime: string
  participants: number
  purpose: string | null
  contactPhone: string
  status: ReservationStatus
  remark: string | null
  createdAt: string
}

/** 创建预约请求（接口设计.md 9.7.1.1 请求体） */
export interface ReservationCreateDTO {
  timeslotId: number
  participants: number
  purpose?: string
  contactPhone?: string
  remark?: string
}

/** 预约操作备注请求体（接口设计.md 9.7.1.4 / 9.7.1.5） */
export interface ReservationActionDTO {
  remark?: string
}

/** 预约操作原因请求体（接口设计.md 9.7.1.6 / 9.7.1.7 / 9.7.1.8） */
export interface ReservationReasonDTO {
  reason: string
}

/** 预约列表查询参数（接口设计.md 9.7.1.3） */
export interface ReservationListQuery extends PageQuery {
  status?: ReservationStatus
  resourceId?: number
  startDate?: string
  endDate?: string
}

/** 资源可预约时段（接口设计.md 9.7.1.9 响应 data 元素） */
export interface IAvailableTimeslot {
  timeslotId: number
  date: string
  startTime: string
  endTime: string
  maxBookings: number
  currentBookings: number
  status: ResourceTimeslotStatus
}

/** 可预约时段查询参数（接口设计.md 9.7.1.9，ISO 8601 日期） */
export interface AvailableTimeslotQuery {
  startDate: string
  endDate: string
}

/** 违约记录（接口设计.md 9.7.2.1 响应 records 元素） */
export interface IViolationRecord {
  id: number
  residentId: number
  residentName: string
  violationType: ViolationType
  sourceId: number
  sourceNumber: string
  reason: string
  handlerId: number
  handlerName: string
  createdAt: string
}

/** 违约记录列表查询参数（接口设计.md 9.7.2.1） */
export interface ViolationListQuery extends PageQuery {
  type?: ViolationType
}
