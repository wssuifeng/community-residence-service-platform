import { http } from '@/common/web/utils/request'
import type {
  ICommunityOption,
  IStaffWorkloadStats,
  IDashboardStats,
  IResidentStats,
  IResourceStats,
  IWorkOrderStats,
  StatisticsQuery
} from '@/common/statistics/types/statistics'

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
export function getResidentStats(query?: StatisticsQuery) {
  return http.get<IResidentStats>('/statistics/residents', query)
}

/** 资源预约统计（接口设计.md 9.9.1.4） */
export function getResourceStats(query?: StatisticsQuery) {
  return http.get<IResourceStats>('/statistics/resources', query)
}

/** 看板小区筛选下拉（后端补充端点） */
export function getCommunityOptions() {
  return http.get<ICommunityOption[]>('/statistics/communities')
}

/** 本人派入/提交事件统计；不接受staffId以扩大人员范围。 */
export function getStaffWorkloadStats(days = 7) {
  return http.get<IStaffWorkloadStats>('/statistics/staff/trend', { days })
}
