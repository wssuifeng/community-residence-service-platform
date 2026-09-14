/** C1 社区基础信息管理类型定义（接口设计.md §9.1） */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 状态枚举 ---------------------------------- */

/** 社区状态（接口设计.md 9.1.1） */
export type CommunityStatus = 'ACTIVE' | 'INACTIVE'

/** 社区状态中文标签 */
export const communityStatusLabels: Record<CommunityStatus, string> = {
  ACTIVE: '运营中',
  INACTIVE: '已停用'
}

/** 房屋状态（接口设计.md 9.1.4.6） */
export type HouseStatus = 'VACANT' | 'OCCUPIED' | 'RESERVED' | 'MAINTENANCE'

/** 房屋状态中文标签 */
export const houseStatusLabels: Record<HouseStatus, string> = {
  VACANT: '空置',
  OCCUPIED: '已入住',
  RESERVED: '预留',
  MAINTENANCE: '维护中'
}

/** 房屋属性类型（接口设计.md 9.1.4.1，文档示例值 APARTMENT） */
export type PropertyType = 'APARTMENT'

/** 房屋属性类型中文标签 */
export const propertyTypeLabels: Record<PropertyType, string> = {
  APARTMENT: '公寓'
}

/** 公共资源类型（2026-09-12 对齐后端 PublicResource @Schema：MEETING_ROOM/GYM/PARKING，
 * 原 SPORTS 为接口文档示例值，后端枚举中不存在，见任务 3 报告数据决策） */
export type ResourceType = 'MEETING_ROOM' | 'GYM' | 'PARKING'

/** 公共资源类型中文标签 */
export const resourceTypeLabels: Record<ResourceType, string> = {
  MEETING_ROOM: '会议室',
  GYM: '健身房',
  PARKING: '停车位'
}

/** 资源预约单位（接口设计.md 9.1.5.1，文档示例值 HOURLY） */
export type BookingUnit = 'HOURLY'

/** 资源预约单位中文标签 */
export const bookingUnitLabels: Record<BookingUnit, string> = {
  HOURLY: '按小时'
}

/** 公共资源状态（接口设计.md 9.1.5，文档示例值 AVAILABLE） */
export type ResourceStatus = 'AVAILABLE'

/** 公共资源状态中文标签 */
export const resourceStatusLabels: Record<ResourceStatus, string> = {
  AVAILABLE: '可用'
}

/* ---------------------------------- 社区 ---------------------------------- */

/** 社区实体（接口设计.md 9.1.1.1 响应） */
export interface ICommunity {
  id: number
  name: string
  address: string
  contactPhone?: string
  contactPerson?: string
  description?: string
  status: CommunityStatus
  createdAt: string
  updatedAt: string
}

/** 创建社区请求（接口设计.md 9.1.1.1 请求体） */
export interface ICreateCommunityDTO {
  name: string
  address: string
  contactPhone?: string
  contactPerson?: string
  description?: string
}

/** 更新社区请求（接口设计.md 9.1.1.2，请求体同创建） */
export type IUpdateCommunityDTO = ICreateCommunityDTO

/** 更新社区状态请求（接口设计.md 9.1.1.5 请求体） */
export interface IUpdateCommunityStatusDTO {
  status: CommunityStatus
}

/** 社区列表查询参数（接口设计.md 9.1.1.4） */
export interface ICommunityQuery extends PageQuery {
  status?: CommunityStatus
  keyword?: string
}

/* ---------------------------------- 楼栋 ---------------------------------- */

/** 楼栋实体（后端 BuildingVO：floors 字段，2026-09-08 验收对齐） */
export interface IBuilding {
  id: number
  communityId: number
  communityName: string
  name: string
  floors: number
  description?: string
  createdAt: string
}

/** 创建/更新楼栋请求（后端 CreateBuildingDTO：floors） */
export interface IBuildingDTO {
  communityId: number
  name: string
  floors: number
  description?: string
}

/** 楼栋列表查询参数（接口设计.md 9.1.2.5） */
export interface IBuildingQuery extends PageQuery {
  keyword?: string
}

/* ---------------------------------- 单元 ---------------------------------- */

/** 单元实体（后端 UnitVO：name + description，无数值楼层/户数字段；
 * buildingName 后端可空，展示一律以树/筛选上下文为准） */
export interface IUnit {
  id: number
  buildingId: number
  buildingName?: string
  communityId: number
  name: string
  description?: string
  createdAt: string
}

/** 创建/更新单元请求（后端 CreateUnitDTO：buildingId + name + description） */
export interface IUnitDTO {
  buildingId: number
  name: string
  description?: string
}

/* ---------------------------------- 房屋 ---------------------------------- */

/** 房屋实体（2026-09-12 对齐后端 HouseVO：无 buildingName/unitNumber 字段，
 * unitName 后端可空；楼栋/单元展示名由前端以筛选/树上下文补全，见任务 3 报告） */
export interface IHouse {
  id: number
  unitId: number
  buildingName?: string
  unitName?: string
  houseNumber: string
  floor: number
  area?: number
  roomCount?: number
  layout?: string
  orientation?: string
  status: HouseStatus
  description?: string
  createdAt: string
}

/** 创建/更新房屋请求（后端 CreateHouseDTO：roomCount/layout，无 propertyType/monthlyRent） */
export interface IHouseDTO {
  unitId: number
  houseNumber: string
  floor: number
  area?: number
  roomCount?: number
  layout?: string
  orientation?: string
  status?: HouseStatus
  description?: string
}

/** 更新房屋状态请求（接口设计.md 9.1.4.6 请求体） */
export interface IUpdateHouseStatusDTO {
  status: HouseStatus
  remark?: string
}

/** 房屋列表查询参数（接口设计.md 9.1.4.5） */
export interface IHouseQuery extends PageQuery {
  status?: HouseStatus
}

/** 房屋状态变更历史实体（接口设计.md 9.1.4.7 响应） */
export interface IHouseStatusHistory {
  id: number
  houseId: number
  oldStatus: HouseStatus | null
  newStatus: HouseStatus
  remark?: string
  operatorId: number
  operatorName: string
  createdAt: string
}

/* ---------------------------------- 公共资源 ---------------------------------- */

/** 公共资源实体（接口设计.md 9.1.5.1 响应） */
export interface IPublicResource {
  id: number
  communityId: number
  communityName: string
  name: string
  type: ResourceType
  location?: string
  capacity?: number
  openTime?: string
  closeTime?: string
  bookingUnit?: BookingUnit
  advanceBookingDays?: number
  description?: string
  rules?: string
  status: ResourceStatus
  createdAt: string
}

/** 创建/更新公共资源请求（接口设计.md 9.1.5.1 请求体，9.1.5.2 同） */
export interface IPublicResourceDTO {
  communityId: number
  name: string
  type: ResourceType
  location?: string
  capacity?: number
  openTime?: string
  closeTime?: string
  bookingUnit?: BookingUnit
  advanceBookingDays?: number
  description?: string
  rules?: string
}

/** 资源列表查询参数（接口设计.md 9.1.5.5） */
export interface IPublicResourceQuery extends PageQuery {
  type?: ResourceType
  status?: ResourceStatus
}

/* ---------------------------------- 资源时段 ---------------------------------- */

/** 资源时段实体（2026-09-12 对齐后端 ResourceTimeslotVO：周循环模板
 * dayOfWeek 1-7 + isAvailable；接口设计.md 9.1.6 的按日期 date/maxBookings
 * 模型为漂移定义，与房源时段 housing.ts 同款修正） */
export interface IResourceTimeslot {
  id: number
  resourceId: number
  resourceName?: string
  /** 星期几：1-周一 … 7-周日（后端 CreateTimeSlotDTO @Schema） */
  dayOfWeek: number
  startTime: string
  endTime: string
  isAvailable: number
  createdAt: string
}

/** 创建资源时段请求（后端 CreateTimeSlotDTO：周模板） */
export interface ICreateTimeslotDTO {
  dayOfWeek: number
  startTime: string
  endTime: string
  isAvailable?: number
}

/** 更新资源时段请求（后端同创建） */
export type IUpdateTimeslotDTO = ICreateTimeslotDTO

/** 资源时段列表查询参数（后端按资源分页，无额外过滤参数） */
export type ITimeslotQuery = PageQuery
