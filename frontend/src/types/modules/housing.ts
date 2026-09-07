import type { PageQuery } from '@/types/api'

/** 房源状态（接口设计.md 9.12.1.6：可租/已预订/已出租/已下架） */
export type HousingStatus = 'AVAILABLE' | 'RESERVED' | 'RENTED' | 'OFFLINE'

/** 房源状态中文标签 */
export const housingStatusLabels: Record<HousingStatus, string> = {
  AVAILABLE: '可租',
  RESERVED: '已预订',
  RENTED: '已出租',
  OFFLINE: '已下架'
}

/** 看房预约状态（接口设计.md 9.12.2，状态机：待确认→已预约→已完成 + 已取消/已违约） */
export type ViewingAppointmentStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'VIOLATED'

/** 看房预约状态中文标签 */
export const viewingAppointmentStatusLabels: Record<ViewingAppointmentStatus, string> = {
  PENDING: '待确认',
  CONFIRMED: '已预约',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  VIOLATED: '已违约'
}

/** 房源看房时段状态（接口设计.md 9.12.2.8 响应示例） */
export type HousingTimeslotStatus = 'AVAILABLE' | 'FULL'

/** 房源看房时段状态中文标签 */
export const housingTimeslotStatusLabels: Record<HousingTimeslotStatus, string> = {
  AVAILABLE: '可预约',
  FULL: '已约满'
}

/** 房源（接口设计.md 9.12.1.1 / 9.12.1.4 响应 data） */
export interface IHousing {
  id: number
  communityId: number
  communityName: string
  houseId: number
  houseAddress: string
  title: string
  description: string
  monthlyRent: number
  depositAmount: number | null
  availableDate: string
  contactPerson: string
  contactPhone: string | null
  images: string[]
  tags: string[]
  status: HousingStatus
  viewCount: number
  createdAt: string
}

/** 创建/更新房源请求（接口设计.md 9.12.1.1 请求体，更新同 9.12.1.2） */
export interface HousingSaveDTO {
  communityId: number
  houseId: number
  title: string
  description: string
  monthlyRent: number
  depositAmount?: number
  availableDate: string
  contactPerson: string
  contactPhone?: string
  images: string[]
  tags?: string[]
}

/** 房源列表查询参数（接口设计.md 9.12.1.5） */
export interface HousingListQuery extends PageQuery {
  communityId?: number
  status?: HousingStatus
  minRent?: number
  maxRent?: number
  keyword?: string
}

/** 更新房源状态请求（接口设计.md 9.12.1.6 请求体） */
export interface HousingStatusUpdateDTO {
  status: HousingStatus
  remark?: string
}

/** 看房预约单（接口设计.md 9.12.2.1 / 9.12.2.2 响应 data） */
export interface IViewingAppointment {
  id: number
  appointmentNumber: string
  residentId: number | null
  housingId: number
  housingTitle: string
  timeslotId: number
  appointmentDate: string
  startTime: string
  endTime: string
  visitorName: string
  visitorPhone: string
  visitorCount: number
  status: ViewingAppointmentStatus
  remark: string | null
  createdAt: string
}

/** 创建看房预约请求（接口设计.md 9.12.2.1 请求体，游客可提交） */
export interface ViewingAppointmentCreateDTO {
  housingId: number
  timeslotId: number
  visitorName: string
  visitorPhone?: string
  visitorCount: number
  remark?: string
}

/** 看房预约操作备注请求体（接口设计.md 9.12.2.4 / 9.12.2.5） */
export interface ViewingAppointmentActionDTO {
  remark?: string
}

/** 看房预约操作原因请求体（接口设计.md 9.12.2.6 / 9.12.2.7） */
export interface ViewingAppointmentReasonDTO {
  reason: string
}

/** 看房预约列表查询参数（接口设计.md 9.12.2.3） */
export interface ViewingAppointmentListQuery extends PageQuery {
  status?: ViewingAppointmentStatus
  housingId?: number
  startDate?: string
  endDate?: string
}

/** 房源可预约看房时段（接口设计.md 9.12.2.8 响应 data 元素） */
export interface IAvailableViewingTimeslot {
  timeslotId: number
  date: string
  startTime: string
  endTime: string
  maxBookings: number
  currentBookings: number
  status: HousingTimeslotStatus
}

/** 可预约看房时段查询参数（接口设计.md 9.12.2.8，ISO 8601 日期） */
export interface AvailableViewingTimeslotQuery {
  startDate: string
  endDate: string
}

/** 房源看房时段（接口设计.md 9.12.3.1 响应 data） */
export interface IHousingTimeslot {
  id: number
  housingId: number
  housingTitle: string
  date: string
  startTime: string
  endTime: string
  maxBookings: number
  currentBookings: number
  status: HousingTimeslotStatus
  createdAt: string
}

/** 创建/更新房源时段请求（接口设计.md 9.12.3.1 请求体，更新同 9.12.3.2） */
export interface HousingTimeslotSaveDTO {
  date: string
  startTime: string
  endTime: string
  maxBookings?: number
  status?: HousingTimeslotStatus
}

/** 房源时段列表查询参数（接口设计.md 9.12.3.4） */
export interface HousingTimeslotListQuery extends PageQuery {
  startDate?: string
  endDate?: string
  status?: HousingTimeslotStatus
}
