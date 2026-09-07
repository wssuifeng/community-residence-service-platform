/** 运营看板-居民居住总览（接口设计.md 9.9.1.1 data.overview） */
export interface IDashboardOverview {
  totalResidents: number
  activeResidents: number
  totalHouses: number
  occupiedHouses: number
  occupancyRate: number
}

/** 运营看板-工单统计（接口设计.md 9.9.1.1 data.workOrders） */
export interface IDashboardWorkOrders {
  total: number
  pending: number
  inProgress: number
  completed: number
  avgCompletionTime: number
  satisfactionRate: number
}

/** 运营看板-资源预约统计（接口设计.md 9.9.1.1 data.resources） */
export interface IDashboardResources {
  totalReservations: number
  completionRate: number
  violationCount: number
}

/** 运营看板-反馈统计（接口设计.md 9.9.1.1 data.feedbacks） */
export interface IDashboardFeedbacks {
  total: number
  open: number
  inProgress: number
  closed: number
  avgResponseTime: number
}

/** 运营看板聚合数据（接口设计.md 9.9.1.1 响应 data） */
export interface IDashboardStats {
  overview: IDashboardOverview
  workOrders: IDashboardWorkOrders
  resources: IDashboardResources
  feedbacks: IDashboardFeedbacks
}

/** 运营看板查询参数（接口设计.md 9.9.1.1） */
export interface DashboardStatsQuery {
  communityId?: number
  startDate?: string
  endDate?: string
}

/** 统计分组维度（接口设计.md 9.9.1.2） */
export type StatisticsGroupBy = 'DAY' | 'WEEK' | 'MONTH' | 'CATEGORY' | 'ASSIGNEE'

/** 状态分布（byStatus 数组元素） */
export interface IStatusDistribution {
  status: string
  count: number
}

/** 工单类别分布（接口设计.md 9.9.1.2 byCategory 元素） */
export interface ICategoryDistribution {
  categoryName: string
  count: number
}

/** 工单趋势点（接口设计.md 9.9.1.2 trend 元素） */
export interface IWorkOrderTrend {
  date: string
  count: number
}

/** 工单统计汇总（接口设计.md 9.9.1.2 summary） */
export interface IWorkOrderSummary {
  total: number
  avgCompletionTime: number
  satisfactionRate: number
}

/** 工单统计（接口设计.md 9.9.1.2 响应 data） */
export interface IWorkOrderStats {
  summary: IWorkOrderSummary
  byStatus: IStatusDistribution[]
  byCategory: ICategoryDistribution[]
  trend: IWorkOrderTrend[]
}

/** 工单统计查询参数（接口设计.md 9.9.1.2，起止日期必填） */
export interface WorkOrderStatsQuery {
  communityId?: number
  startDate: string
  endDate: string
  groupBy?: StatisticsGroupBy
}

/** 居民统计汇总（接口设计.md 9.9.1.3 summary） */
export interface IResidentSummary {
  total: number
  active: number
  frozen: number
  newThisMonth: number
}

/** 注册趋势点（接口设计.md 9.9.1.3 registrationTrend 元素） */
export interface IMonthTrend {
  month: string
  count: number
}

/** 居民统计（接口设计.md 9.9.1.3 响应 data） */
export interface IResidentStats {
  summary: IResidentSummary
  byStatus: IStatusDistribution[]
  registrationTrend: IMonthTrend[]
}

/** 居民统计查询参数（接口设计.md 9.9.1.3） */
export interface ResidentStatsQuery {
  communityId?: number
}

/** 资源预约统计汇总（接口设计.md 9.9.1.4 summary） */
export interface IResourceStatsSummary {
  totalReservations: number
  completionRate: number
  violationCount: number
  violationRate: number
}

/** 资源预约量分布（接口设计.md 9.9.1.4 byResource 元素） */
export interface IResourceUsage {
  resourceName: string
  count: number
  completionRate: number
}

/** 资源预约统计（接口设计.md 9.9.1.4 响应 data） */
export interface IResourceStats {
  summary: IResourceStatsSummary
  byResource: IResourceUsage[]
  byStatus: IStatusDistribution[]
}

/** 资源预约统计查询参数（接口设计.md 9.9.1.4，起止日期必填） */
export interface ResourceStatsQuery {
  communityId?: number
  startDate: string
  endDate: string
}

/** 服务评价统计汇总（接口设计.md 9.9.1.5 summary） */
export interface IEvaluationSummary {
  total: number
  avgRating: number
  satisfactionRate: number
  unsatisfiedCount: number
}

/** 评分分布（接口设计.md 9.9.1.5 ratingDistribution 元素） */
export interface IRatingDistribution {
  rating: number
  count: number
}

/** 服务人员评价分布（接口设计.md 9.9.1.5 byAssignee 元素） */
export interface IAssigneeRating {
  assigneeName: string
  count: number
  avgRating: number
}

/** 服务评价统计（接口设计.md 9.9.1.5 响应 data） */
export interface IEvaluationStats {
  summary: IEvaluationSummary
  ratingDistribution: IRatingDistribution[]
  byAssignee: IAssigneeRating[]
}

/** 服务评价统计查询参数（接口设计.md 9.9.1.5，起止日期必填） */
export interface EvaluationStatsQuery {
  communityId?: number
  startDate: string
  endDate: string
  assigneeId?: number
}
