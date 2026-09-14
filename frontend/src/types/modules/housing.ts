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

/** 看房预约状态（架构设计 §6 状态机权威口径：TO_CONFIRM→RESERVED→COMPLETED + 已取消/已违约；接口文档 PENDING/CONFIRMED 为漂移命名） */
export type ViewingAppointmentStatus =
  | 'TO_CONFIRM'
  | 'RESERVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'VIOLATED'

/** 看房预约状态中文标签 */
export const viewingAppointmentStatusLabels: Record<ViewingAppointmentStatus, string> = {
  TO_CONFIRM: '待确认',
  RESERVED: '已预约',
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

/**
 * 房源（2026-09-12 管理端任务 10 以后端 HousingVO.java 逐一核对收口）。
 * 后端实体仅含 id/communityId/houseId/title/description/monthlyRent/deposit/images/status/viewCount/publishTime/createdAt；
 * communityName/houseLocation 由服务层组装。接口设计.md 9.12.1.1 的
 * houseAddress/depositAmount/availableDate/contactPerson/contactPhone 均为漂移命名，
 * 无消费方后已从类型删除。
 */
export interface IHousing {
  id: number
  communityId: number
  communityName: string
  houseId: number
  /** 房屋位置（服务层组装「楼栋+单元+房号」，如「1 号楼1 单元102」） */
  houseLocation: string
  title: string
  description: string
  monthlyRent: number
  /** 押金，未填为 null（接口文档 depositAmount 为漂移命名） */
  deposit: number | null
  images: string[]
  status: HousingStatus
  viewCount: number
  /** 发布时间（创建即发布） */
  publishTime: string
  createdAt: string
  /** 幽灵字段：后端 HousingVO 不返回 layout；保留仅因游客/居民端详情视图仍读取（本任务禁改保护端），运行时恒为 undefined 走兜底文案 */
  layout: string | null
  /** 幽灵字段：后端 HousingVO 不返回 rentType；保留原因同 layout */
  rentType: string
  /** 幽灵字段：后端 HousingVO 不返回 tags；游客/居民端列表与详情仍读取，使用处需空值防御 */
  tags?: string[]
  /** 幽灵字段：后端 HousingVO 不返回 contactPhone；游客/居民端详情仍读取，保留原因同 layout */
  contactPhone: string | null
}

/**
 * 创建/更新房源请求（2026-09-12 对齐后端 CreateHousingDTO：houseId/title/description/monthlyRent/deposit/images）。
 * - 押金字段为 deposit：原 depositAmount 后端不识别，押金一直被静默丢弃，收口后可正常保存；
 * - communityId 由后端从 houseId 推导，不接收（前端级联选择仅作定位房屋的本地状态）；
 * - images 为逗号分隔 URL 单字符串（后端按 String 存储并以逗号拆分返回）；
 * - availableDate/contactPerson/contactPhone/tags 后端无对应字段，已删。
 */
export interface HousingSaveDTO {
  houseId: number
  title: string
  description: string
  monthlyRent: number
  deposit?: number
  images: string
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

/**
 * 看房预约单（2026-09-12 以后端 ViewingAppointmentVO.java 核对收口）。
 * 接口设计.md 9.12.2.1 的 appointmentNumber/timeslotId/visitorPhone/visitorCount/residentId 均为漂移命名：
 * visitorPhone→contactPhone、residentId→userId 已收口；appointmentNumber/visitorCount 为
 * 幽灵字段（后端不返回），保留仅因居民端列表仍展示（本任务禁改保护端）。
 */
export interface IViewingAppointment {
  id: number
  /** 预约人用户ID（游客预约为 null；接口文档 residentId 为漂移命名） */
  userId: number | null
  housingId: number
  housingTitle: string
  communityId: number
  appointmentDate: string
  startTime: string
  endTime: string
  visitorName: string
  /** 联系电话（接口文档 visitorPhone 为漂移命名） */
  contactPhone: string
  status: ViewingAppointmentStatus
  remark: string | null
  createdAt: string
  /** 幽灵字段：后端 VO 不返回 appointmentNumber；居民端列表仍展示，运行时恒为 undefined */
  appointmentNumber: string
  /** 幽灵字段：后端 VO 不返回 visitorCount；居民端列表仍展示，保留原因同上 */
  visitorCount: number
}

/** 创建看房预约请求（接口设计.md 9.12.2.1 请求体，游客可提交） */
export interface ViewingAppointmentCreateDTO {
  /** 后端 CreateViewingAppointmentDTO：日期+起止时间直传（接口文档的 timeslotId 为漂移命名） */
  housingId: number
  appointmentDate: string
  startTime: string
  endTime: string
  visitorName: string
  contactPhone: string
  remark?: string
}

/** 看房预约操作请求体（后端 ReservationActionDTO：确认/完成/取消/违约四端点共用，reason 必填。
 * 接口设计.md 9.12.2.4/9.12.2.5 的 remark 选填体为漂移契约，2026-09-12 收口） */
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
/** 房源时段（后端 HousingTimeslotVO：周循环模板 dayOfWeek+isAvailable，接口文档的按日期时段为漂移模型） */
export interface IHousingTimeslot {
  id: number
  housingId: number
  dayOfWeek: number
  startTime: string
  endTime: string
  isAvailable: number
  createdAt: string
  updatedAt: string
}

/** 创建/更新房源时段请求（后端 CreateHousingTimeslotDTO：周模板） */
export interface HousingTimeslotSaveDTO {
  dayOfWeek: number
  startTime: string
  endTime: string
  isAvailable?: number
}

/** 房源时段列表查询参数（接口设计.md 9.12.3.4） */
export interface HousingTimeslotListQuery extends PageQuery {
  startDate?: string
  endDate?: string
  status?: HousingTimeslotStatus
}
