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
  /** 入住申请自动通过：0-关闭（人工审核）, 1-开启（提交即通过），V18 新增 */
  autoApproveResidence?: number
  /** 自动通过时的默认租期月数（人工审核路径不使用），V18 新增 */
  defaultLeaseMonths?: number
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
  /** 入住申请自动通过开关（0/1；缺省 0） */
  autoApproveResidence?: number
  /** 自动通过默认租期月数（1~120；缺省 12） */
  defaultLeaseMonths?: number
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

/* ==================== 批量管理社区（V19 批次，仅超管） ==================== */

/** 批量操作失败项 */
export interface IBatchFailure {
  /** 社区ID（结构批量生成时可能为空） */
  id?: number
  name?: string
  reason: string
  level?: string
}

/** 批量操作结果（部分成功语义：成功 ID 与逐条失败原因） */
export interface IBatchOperationResult {
  successIds: number[]
  failures: IBatchFailure[]
}

/* ==================== 结构链一次性批量生成（V19 批次） ==================== */

/**
 * 结构批量生成请求（POST /structures/batch-generate）。
 * 语义：按楼栋序号区间生成楼栋，可选为每栋生成 N 个单元，可选为每单元生成
 * 楼层×每层户数 的房屋；房号 = 前缀 + 楼层 + 补零(序号)（宽度默认 2，与结构总览一致）。
 */
export interface IStructureBatchGenerateDTO {
  communityId: number
  /** 楼栋名前缀（如 "C10"） */
  buildingNamePrefix?: string
  /** 楼栋起始序号 */
  buildingStartNo: number
  /** 楼栋结束序号（与起始合计上限 60 栋） */
  buildingEndNo: number
  /** 楼栋名后缀（如 "号楼"） */
  buildingNameSuffix?: string
  /** 每栋生成单元数（0 或缺省=不生成单元） */
  unitCountPerBuilding?: number
  unitNamePrefix?: string
  /** 单元名后缀（缺省「单元」，即 1单元/2单元） */
  unitNameSuffix?: string
  /** 每单元楼层数（0 或缺省=不生成房屋） */
  floorsPerUnit?: number
  /** 每层户数 */
  housesPerFloor?: number
  /** 房号前缀（如 "A-"） */
  houseNumberPrefix?: string
  /** 房号序号补零宽度（缺省 2） */
  houseNumberWidth?: number
  /**
   * 跳过项表达式（逗号/顿号/空格分隔，与结构总览精确建房同语法）：
   * `4`=第4层整层；`04`=所有楼层的 4 号；`*:4`=所有楼层 4 号；`4:1`=第4层1号；
   * `104`=基础门牌号（1层4号）；`A-101`=完整房号精确匹配
   */
  skipItems?: string
  /** 房屋初始状态（缺省 VACANT） */
  houseStatus?: HouseStatus
  /** 房屋建筑面积（生成房屋时必填，后端 CreateHouseDTO 约束） */
  area?: number
  roomCount?: number
  description?: string
  /** true=仅预览不落库（零写入） */
  dryRun?: boolean
}

/** 结构批量生成结果（dryRun 时仅预览字段有效） */
export interface IStructureBatchGenerateResult {
  dryRun: boolean
  buildingsCreated: number
  unitsCreated: number
  housesCreated: number
  /** 预览：楼栋名（最多 20 条） */
  previewBuildings: string[]
  /** 预览：单元位置（最多 20 条） */
  previewUnits: string[]
  /** 预览：房屋完整位置（最多 50 条） */
  previewHouses: string[]
  /** 被跳过表达式排除的房屋数 */
  skippedCount: number
  /** 因同单元房号已存在而跳过的数量 */
  dedupedCount: number
  failures: IBatchFailure[]
}
