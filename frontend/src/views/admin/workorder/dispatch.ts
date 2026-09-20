/**
 * 调度工作台视图层共用口径（V19）。
 * 契约层（types/modules/workorder.ts、api/workorder.ts）已冻结，此处只放「阶段分组 + 展示
 * 格式化 + 客户端排序」，供看板、列表表格与详情头部复用，避免同一口径在多处各写一份。
 */
import type { IWorkOrder, WorkOrderPriority, WorkOrderSort, WorkOrderStatus } from '@/types/modules/workorder'

/** 调度看板阶段列（列顺序即工单处置推进顺序） */
export interface IDispatchStage {
  /** 阶段标识（看板列 key，非持久化字段） */
  key: string
  /** 阶段名（列头主文案） */
  label: string
  /** 列头副文案：说明这一列要做什么 */
  hint: string
  /** 该列聚合的工单状态（统计卡计数与列内查询按此展开） */
  statuses: WorkOrderStatus[]
  /** 语义色档（列头小圆点与计数徽章着色） */
  tone: 'pending' | 'processing' | 'completed' | 'info'
}

/** 五个处置阶段：看板默认只呈现这五列，已关闭/已驳回/已取消不占屏（列表视图仍可查） */
export const DISPATCH_STAGES: IDispatchStage[] = [
  {
    key: 'pool',
    label: '待处置',
    hint: '待受理 / 待派单，需尽快指派',
    statuses: ['PENDING', 'TO_ASSIGN'],
    tone: 'pending'
  },
  {
    key: 'responding',
    label: '待响应',
    hint: '已派单未接单 / 已接单待开工',
    statuses: ['ASSIGNED', 'ACCEPTED'],
    tone: 'info'
  },
  {
    key: 'working',
    label: '处理中',
    hint: '服务人员正在处理',
    statuses: ['IN_PROGRESS'],
    tone: 'processing'
  },
  {
    key: 'confirming',
    label: '待居民确认',
    hint: '完成待居民确认，需催办',
    statuses: ['TO_CONFIRM'],
    tone: 'pending'
  },
  {
    key: 'done',
    label: '近期完成',
    hint: '已完成，待评价或归档',
    statuses: ['COMPLETED'],
    tone: 'completed'
  }
]

/** 派单动作可用状态（与后端 ALLOWED_TRANSITIONS 同口径：已派单=改派） */
const ASSIGNABLE_STATUSES: WorkOrderStatus[] = ['PENDING', 'TO_ASSIGN', 'ASSIGNED']

/** 该工单当前是否可派单/改派 */
export function canAssignOrder(order: IWorkOrder): boolean {
  return ASSIGNABLE_STATUSES.includes(order.status)
}

/** 是否为改派（已派单状态下再派一次） */
export function isReassignOrder(order: IWorkOrder): boolean {
  return order.status === 'ASSIGNED'
}

/**
 * 等待时长文案：把 waitedMinutes 转成「45 分钟」/「2 小时 15 分」/「3 天 2 小时」。
 * 前端只做展示换算，超时与否由后端 dispatchFlag 判定，两处口径不重复计算。
 */
export function formatWaited(minutes?: number | null): string {
  if (minutes === undefined || minutes === null || Number.isNaN(minutes)) return ''
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${Math.floor(minutes)} 分钟`
  if (minutes < 1440) {
    const hours = Math.floor(minutes / 60)
    const rest = Math.floor(minutes % 60)
    return rest > 0 ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
  }
  const days = Math.floor(minutes / 1440)
  const restHours = Math.floor((minutes % 1440) / 60)
  return restHours > 0 ? `${days} 天 ${restHours} 小时` : `${days} 天`
}

/** 紧急度权重（PRIORITY 排序与卡片紧急标记共用） */
export function priorityWeight(priority: WorkOrderPriority): number {
  if (priority === 'URGENT') return 4
  if (priority === 'HIGH') return 3
  if (priority === 'NORMAL') return 2
  return 1
}

/**
 * 客户端合并排序：看板一列聚合多个状态（各状态分页取回后需合并），
 * 语义与后端的三种 sort 口径保持一致（DEFAULT=ID 倒序；WAIT_DESC=提交时间升序近似等待最久在前；
 * PRIORITY=紧急优先 + 提交时间升序）
 */
export function compareWorkOrders(sort: WorkOrderSort, a: IWorkOrder, b: IWorkOrder): number {
  if (sort === 'WAIT_DESC') {
    const byTime = a.createdAt.localeCompare(b.createdAt)
    return byTime !== 0 ? byTime : b.id - a.id
  }
  if (sort === 'PRIORITY') {
    const byPriority = priorityWeight(b.priority) - priorityWeight(a.priority)
    if (byPriority !== 0) return byPriority
    const byTime = a.createdAt.localeCompare(b.createdAt)
    return byTime !== 0 ? byTime : a.id - b.id
  }
  return b.id - a.id
}

/** 状态 → 所属阶段 key（统计卡激活态判断用） */
export function stageKeyOfStatus(status: WorkOrderStatus): string {
  return DISPATCH_STAGES.find((stage) => stage.statuses.includes(status))?.key ?? ''
}
