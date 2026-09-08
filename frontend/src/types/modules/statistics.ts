/**
 * C9 运营统计类型。
 * 2026-09-08 前端自测联调对齐：后端 StatisticsService 实际返回扁平
 * Map 结构（非接口设计.md 9.9.1 示例的嵌套 summary/trend 结构），
 * 类型以后端实现契约为准，字段名与 StatisticsService 逐一对应。
 */

import type { PageQuery } from '@/types/api'

/** 看板聚合数据（后端 StatisticsService.dashboard，扁平键） */
export interface IDashboardStats {
  communityCount: number
  buildingCount: number
  houseCount: number
  occupiedHouseCount: number
  residentCount: number
  activeLeaseCount: number
  expiringLeaseCount: number
  workOrderTotal: number
  workOrderPending: number
  workOrderProcessing: number
  workOrderCompleted: number
  reservationTotal: number
  reservationPending: number
  violationCount: number
  evaluationTotal: number
  /** 近 7 日工单提交趋势：日期字符串 → 数量 */
  workOrderTrend7d: Record<string, number>
  /** 工单状态分布：状态码 → 数量 */
  workOrderStatusDistribution: Record<string, number>
  /** 房屋状态分布：状态码 → 数量 */
  houseStatusDistribution: Record<string, number>
  /** 评价分档分布：评分（数字字符串键）→ 数量 */
  ratingDistribution: Record<string, number>
}

/** 工单统计（专项，后端 workOrderStatistics） */
export interface IWorkOrderStats {
  total: number
  byStatus: Record<string, number>
  byPriority: Record<string, number>
}

/** 居民统计（专项，后端 residentStatistics） */
export interface IResidentStats {
  total: number
  byStatus: Record<string, number>
}

/** 资源预约统计（专项，后端 reservationStatistics） */
export interface IResourceStats {
  total: number
  byStatus: Record<string, number>
}

/** 看板社区筛选下拉项（后端 communityOptions） */
export interface ICommunityOption {
  id: number
  name: string
}

/** 统计查询参数（communityId 可选过滤） */
export interface StatisticsQuery {
  communityId?: number
}

/** 兼容导出：旧分页查询参数类型占位（后端统计接口无分页） */
export type DashboardStatsQuery = StatisticsQuery
export type WorkOrderStatsQuery = StatisticsQuery
export type ResidentStatsQuery = StatisticsQuery
export type ResourceStatsQuery = StatisticsQuery

/** 兼容导出：图表数据（由看板聚合数据的 Record 派生，不再单独请求） */
export interface IDistributionItem {
  name: string
  value: number
}

export type { PageQuery }
