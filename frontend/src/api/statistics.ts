import { http } from '@/utils/request'
import type {
  ICommunityOption,
  IDashboardStats,
  IResidentStats,
  IResourceStats,
  IWorkOrderStats,
  StatisticsQuery
} from '@/types/modules/statistics'

/**
 * C9 运营统计接口（接口设计.md §9.9；2026-09-08 联调对齐后端
 * StatisticsController 实际实现：5 个端点均为扁平 Map 结构）
 */

/** 运营看板聚合数据（接口设计.md 9.9.1.1） */
export function getDashboardStats(query?: StatisticsQuery) {
  return http.get<IDashboardStats>('/statistics/dashboard', query)
}

/** 工单统计（接口设计.md 9.9.1.2） */
export function getWorkOrderStats(query?: StatisticsQuery) {
  return http.get<IWorkOrderStats>('/statistics/work-orders', query)
}

/** 居民统计（接口设计.md 9.9.1.3） */
export function getResidentStats() {
  return http.get<IResidentStats>('/statistics/residents')
}

/** 资源预约统计（接口设计.md 9.9.1.4） */
export function getResourceStats(query?: StatisticsQuery) {
  return http.get<IResourceStats>('/statistics/resources', query)
}

/** 看板社区筛选下拉（后端补充端点） */
export function getCommunityOptions() {
  return http.get<ICommunityOption[]>('/statistics/communities')
}
