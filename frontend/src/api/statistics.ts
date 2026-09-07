import { http } from '@/utils/request'
import type {
  DashboardStatsQuery,
  EvaluationStatsQuery,
  IDashboardStats,
  IEvaluationStats,
  IResidentStats,
  IResourceStats,
  IWorkOrderStats,
  ResidentStatsQuery,
  ResourceStatsQuery,
  WorkOrderStatsQuery
} from '@/types/modules/statistics'

/** 运营看板聚合数据（接口设计.md 9.9.1.1） */
export function getDashboardStats(query?: DashboardStatsQuery) {
  return http.get<IDashboardStats>('/statistics/dashboard', query)
}

/** 工单统计（接口设计.md 9.9.1.2） */
export function getWorkOrderStats(query: WorkOrderStatsQuery) {
  return http.get<IWorkOrderStats>('/statistics/work-orders', query)
}

/** 居民统计（接口设计.md 9.9.1.3） */
export function getResidentStats(query?: ResidentStatsQuery) {
  return http.get<IResidentStats>('/statistics/residents', query)
}

/** 资源预约统计（接口设计.md 9.9.1.4） */
export function getResourceStats(query: ResourceStatsQuery) {
  return http.get<IResourceStats>('/statistics/resources', query)
}

/** 服务评价统计（接口设计.md 9.9.1.5） */
export function getEvaluationStats(query: EvaluationStatsQuery) {
  return http.get<IEvaluationStats>('/statistics/evaluations', query)
}
