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

/** 公共资源类型（接口设计.md 9.1.5.1，文档示例值 SPORTS） */
export type ResourceType = 'SPORTS'

/** 公共资源类型中文标签 */
export const resourceTypeLabels: Record<ResourceType, string> = {
  SPORTS: '运动场地'
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

/** 资源时段状态（接口设计.md 9.1.6，文档示例值 AVAILABLE） */
export type TimeslotStatus = 'AVAILABLE'

/** 资源时段状态中文标签 */
export const timeslotStatusLabels: Record<TimeslotStatus, string> = {
  AVAILABLE: '可预约'
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

/** 楼栋实体（接口设计.md 9.1.2.1 响应） */
export interface IBuilding {
  id: number
  communityId: number
  communityName: string
  name: string
  totalFloors: number
  description?: string
  createdAt: string
}

/** 创建/更新楼栋请求（接口设计.md 9.1.2.1 请求体，9.1.2.2 同） */
export interface IBuildingDTO {
  communityId: number
  name: string
  totalFloors: number
  description?: string
}

/** 楼栋列表查询参数（接口设计.md 9.1.2.5） */
export interface IBuildingQuery extends PageQuery {
  keyword?: string
}

/* ---------------------------------- 单元 ---------------------------------- */

/** 单元实体（接口设计.md 9.1.3.1 响应） */
export interface IUnit {
  id: number
  buildingId: number
  buildingName: string
  unitNumber: string
  totalFloors: number
  householdsPerFloor: number
  createdAt: string
}

/** 创建/更新单元请求（接口设计.md 9.1.3.1 请求体，9.1.3.2 同） */
export interface IUnitDTO {
  buildingId: number
  unitNumber: string
  totalFloors: number
  householdsPerFloor: number
}

/* ---------------------------------- 房屋 ---------------------------------- */

/** 房屋实体（接口设计.md 9.1.4.1 响应） */
export interface IHouse {
  id: number
  unitId: number
  buildingName: string
  unitNumber: string
  houseNumber: string
  floor: number
  area?: number
  bedrooms?: number
  livingRooms?: number
  bathrooms?: number
  orientation?: string
  propertyType?: PropertyType
  status: HouseStatus
  monthlyRent?: number
  description?: string
  createdAt: string
}

/** 创建/更新房屋请求（接口设计.md 9.1.4.1 请求体，9.1.4.2 同） */
export interface IHouseDTO {
  unitId: number
  houseNumber: string
  floor: number
  area: number
  bedrooms?: number
  livingRooms?: number
  bathrooms?: number
  orientation?: string
  propertyType?: PropertyType
  status?: HouseStatus
  monthlyRent?: number
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

/** 资源时段实体（接口设计.md 9.1.6.1 响应） */
export interface IResourceTimeslot {
  id: number
  resourceId: number
  resourceName: string
  date: string
  startTime: string
  endTime: string
  maxBookings: number
  currentBookings: number
  status: TimeslotStatus
  createdAt: string
}

/** 创建资源时段请求（接口设计.md 9.1.6.1 请求体） */
export interface ICreateTimeslotDTO {
  date: string
  startTime: string
  endTime: string
  maxBookings?: number
  status?: TimeslotStatus
}

/** 更新资源时段请求（接口设计.md 9.1.6.2，同创建但不含 resourceId） */
export type IUpdateTimeslotDTO = ICreateTimeslotDTO

/** 资源时段列表查询参数（接口设计.md 9.1.6.4） */
export interface ITimeslotQuery extends PageQuery {
  startDate?: string
  endDate?: string
  status?: TimeslotStatus
}
