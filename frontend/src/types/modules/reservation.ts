import type { PageQuery } from '@/types/api'

/** 资源预约状态（状态机：待审核→已预约→已完成 + 已拒绝/已取消/已违约）。
 * 后端实际返回 RESERVED（ReservationVO @Schema），接口文档示例值 CONFIRMED 为漂移命名，
 * 保留 CONFIRMED 仅兼容存量居民端比较逻辑，读取侧一律以 RESERVED 为准 */
export type ReservationStatus =
  | 'PENDING'
  | 'RESERVED'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'VIOLATED'

/** 资源预约状态中文标签 */
export const reservationStatusLabels: Record<ReservationStatus, string> = {
  PENDING: '待审核',
  RESERVED: '已预约',
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

/** 违约类型（后端实际写入值：RESERVATION_NO_SHOW/VIEWING_NO_SHOW，2026-09-12 任务 9
 * 预检核实 ReservationService/ViewingAppointmentService；接口文档示例值
 * RESOURCE_RESERVATION/VIEWING_APPOINTMENT 为漂移命名） */
export type ViolationType = 'RESERVATION_NO_SHOW' | 'VIEWING_NO_SHOW'

/** 违约类型中文标签 */
export const violationTypeLabels: Record<ViolationType, string> = {
  RESERVATION_NO_SHOW: '资源预约',
  VIEWING_NO_SHOW: '看房预约'
}

/** 资源预约单（接口设计.md 9.7.1.1 / 9.7.1.2 响应 data）。
 * 后端 ReservationVO 实际返回 userId/userName/communityId/reserveDate（2026-09-12 任务 9 预检核实），
 * 文档示例字段 reservationNumber/timeslotId/participants 后端不存在，保留仅供存量居民端过渡 */
export interface IResourceReservation {
  id: number
  /** 后端实际返回：预约人 ID */
  userId: number
  /** 后端实际返回：预约人姓名 */
  userName: string
  /** 文档示例字段，后端无此字段 */
  reservationNumber?: string
  residentId?: number
  residentName?: string
  resourceId: number
  resourceName: string
  /** 后端实际返回：所属社区 ID */
  communityId: number
  timeslotId?: number
  /** 接口文档字段；后端实际返回 reserveDate（漂移命名），读取侧优先用 reserveDate */
  reservationDate?: string
  /** 后端实际返回字段：预约日期 */
  reserveDate: string
  startTime: string
  endTime: string
  participants?: number
  purpose: string | null
  contactPhone: string
  status: ReservationStatus
  remark: string | null
  createdAt: string
}

/**
 * 创建预约请求（后端 CreateReservationDTO 实际契约，2026-09-12 R3 预检实测对齐：
 * 接口设计.md 9.7.1.1 示例的 timeslotId/participants 后端字段不存在，照发即 400
 * 「参数[resourceId]资源不能为空」；时段须完全落在资源当日某个可预约模板内，
 * 同一居民同资源同日期存在占用态（待审核/已预约）记录时后端拒绝重复预约）
 */
export interface ReservationCreateDTO {
  resourceId: number
  /** 预约日期（ISO YYYY-MM-DD） */
  reserveDate: string
  /** 开始时间（HH:mm:ss，须与资源时段模板一致） */
  startTime: string
  /** 结束时间（须与资源时段模板一致） */
  endTime: string
  /** 预约用途（最多 200 字符） */
  purpose?: string
  /** 联系电话（选填，填写时须满足后端 ^1[3-9]\d{9}$ 格式） */
  contactPhone?: string
  /** 备注（最多 500 字符） */
  remark?: string
}

/** 预约处置请求（后端 ReservationActionDTO：confirm/complete/reject/cancel/violate 共用，
 * reason @NotBlank——2026-09-12 任务 9 预检核实，文档中的 remark 请求体不存在） */
export interface ReservationActionDTO {
  reason: string
}

/** 预约操作原因请求体（与 ReservationActionDTO 同构，保留别名降低调用侧改动） */
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

/** 违约记录（后端 GET /residents/{residentId}/violations 实际返回 Map 键，
 * 2026-09-12 任务 9 预检核实；文档示例的 residentName/sourceNumber/handlerName
 * 等冗余展示字段后端不返回） */
export interface IViolationRecord {
  id: number
  residentId: number
  violationType: string
  relatedId: number | null
  punishment: string
  remark: string
  createdAt: string
}

/** 违约记录列表查询参数（后端仅实现分页，无类型/时间过滤参数） */
export interface ViolationListQuery extends PageQuery {}
